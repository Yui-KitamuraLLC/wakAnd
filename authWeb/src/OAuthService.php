<?php
declare(strict_types=1);
namespace WakAnd\Auth;

final class OAuthService
{
    public const BASE = 'https://yui.eng.pro/android/wakAnd/';
    public const SCOPE = 'read_stats.projects,write_heartbeats';
    private TokenCipher $cipher;
    public function __construct(private Repository $repo, private Provider $provider, private array $config)
    { $this->cipher = new TokenCipher($config); }

    public function start(string $deviceHash, string $challenge, string $ip): array
    {
        $id = random_bytes(16); $launch = random_bytes(32);
        return $this->repo->transaction(function () use ($id, $launch, $deviceHash, $challenge, $ip) {
            $this->repo->rate('start-global', '', 600, 100, $this->config['rate_limit_key']);
            $this->repo->rate('start-ip', $ip, 600, 10, $this->config['rate_limit_key']);
            $now = $this->repo->now();
            $this->repo->query("INSERT INTO oauth_links (link_id,device_secret_hash,status,created_at,updated_at) VALUES (?,?,'pending',?,?)", [$id,$deviceHash,$now,$now]);
            $this->repo->query('INSERT INTO oauth_link_sessions (link_id,launch_token_hash,verifier_challenge,created_at,expires_at) VALUES (?,?,?,?,?)', [$id,hash('sha256',$launch,true),$challenge,$now,$now+600]);
            return ['link_id'=>encodeId($id), 'browser_url'=>self::BASE . 'oauth/launch?link_id=' . encodeId($id) . '&launch_token=' . encodeId($launch), 'expires_at'=>$now+600];
        });
    }
    public static function cookieName(string $id): string { return 'wakand_' . encodeId($id); }
    public function launch(string $id, string $launch): array
    {
        return $this->repo->transaction(function () use ($id,$launch) {
            $row = $this->recover($this->repo->link($id)); $session = $this->repo->session($id);
            if ($session['expires_at'] <= $this->repo->now()) { return $this->expire($row, $session); }
            if ($row['status'] !== 'pending' || $session['outcome'] !== 'pending' || $session['launch_token_hash'] === null
                || !hash_equals($session['launch_token_hash'],hash('sha256',$launch,true))) { throw new ApiError(400,'invalid_request'); }
            $state = random_bytes(32); $nonce = random_bytes(32);
            $this->repo->update($id, ['state_hash'=>hash('sha256',$state,true),'launch_token_hash'=>null,
                'browser_nonce_hash'=>hash('sha256',$nonce,true),'outcome'=>'browser_started'],true);
            return ['cookie'=>encodeId($nonce), 'url'=>'https://wakatime.com/oauth/authorize?' . http_build_query([
                'client_id'=>$this->config['client_id'],'response_type'=>'code','redirect_uri'=>$this->config['redirect_uri'],
                'scope'=>self::SCOPE,'state'=>encodeId($state)], '', '&', PHP_QUERY_RFC3986)];
        });
    }
    public function callback(string $state, ?string $code, ?string $error, array $cookies): string
    {
        // Read only to find the link; all validation repeats under links -> sessions locks.
        $id = $this->repo->query('SELECT link_id FROM oauth_link_sessions WHERE state_hash=?', [hash('sha256',$state,true)])->fetchColumn();
        if (!is_string($id)) { throw new ApiError(400,'invalid_request'); }
        $operation = $this->repo->transaction(function () use ($id,$state,$code,$error,$cookies) {
            $row = $this->recover($this->repo->link($id)); $session = $this->repo->session($id);
            if ($session['expires_at'] <= $this->repo->now()) { return $this->expire($row,$session); }
            $nonce = decodeId($cookies[self::cookieName($id)] ?? null,32);
            if ($session['state_hash'] === null || !hash_equals($session['state_hash'],hash('sha256',$state,true))
                || $session['browser_nonce_hash'] === null || !hash_equals($session['browser_nonce_hash'],hash('sha256',$nonce,true))
                || $session['state_consumed_at'] !== null || $session['outcome'] !== 'browser_started' || $row['status'] !== 'pending') {
                throw new ApiError(400,'invalid_request');
            }
            $this->repo->update($id,['state_consumed_at'=>$this->repo->now(),'outcome'=>$error === null ? 'exchanging' : 'denied'],true);
            if ($error !== null) {
                $this->repo->update($id,['status'=>'reauth_required','last_error_code'=>'authorization_denied','updated_at'=>$this->repo->now()]);
                return null;
            }
            return $this->reserve($row,'exchanging');
        });
        if ($operation !== null) { $this->execute($operation, fn() => $this->provider->exchange($code)); }
        return self::BASE . 'app/complete?link_id=' . encodeId($id);
    }
    public function authenticate(string $id, string $secret, string $ip): void
    {
        $valid = $this->repo->transaction(function () use ($id,$secret) {
            try { $row=$this->repo->link($id); } catch (ApiError $e) { return false; }
            return $row['device_secret_hash'] !== null && hash_equals($row['device_secret_hash'],hash('sha256',$secret,true))
                && ($row['disconnected_at'] === null || (int)$row['disconnected_at']+604800 > $this->repo->now());
        });
        if (!$valid) {
            $this->repo->transaction(fn() => $this->repo->rate('auth-failure',$ip,60,30,$this->config['rate_limit_key']));
            throw new ApiError(401,'device_unauthorized');
        }
    }
    private function authenticatedRow(string $id, string $secret): array
    {
        $row=$this->recover($this->repo->link($id));
        if ($row['device_secret_hash'] === null || !hash_equals($row['device_secret_hash'],hash('sha256',$secret,true))
            || ($row['disconnected_at'] !== null && (int)$row['disconnected_at']+604800 <= $this->repo->now())) { throw new ApiError(401,'device_unauthorized'); }
        return $row;
    }
    private function usable(array $row): ?ApiError
    {
        if ((int)$row['disconnect_requested'] === 1 || in_array($row['status'],['revoke_pending','revoking','revoked'],true)) { return new ApiError(410,'link_disconnected'); }
        if ($row['status'] === 'reauth_required') { return new ApiError(409,'reauth_required'); }
        if (in_array($row['status'],['exchanging','refreshing'],true)) { return new ApiError(409,'operation_in_progress'); }
        return null;
    }
    public function complete(string $id, string $secret, string $verifier): array
    {
        return $this->repo->transaction(function () use ($id,$secret,$verifier) {
            $row=$this->authenticatedRow($id,$secret); $session=$this->repo->session($id);
            $this->repo->rate('complete',encodeId($id),60,60,$this->config['rate_limit_key']);
            if (!hash_equals($session['verifier_challenge'],hash('sha256',encodeId($verifier),true))) { throw new ApiError(401,'device_unauthorized'); }
            if ($session['expires_at'] <= $this->repo->now()) { return $this->expire($row,$session); }
            if ($failure=$this->usable($row)) { return $failure; }
            if ($row['status'] === 'pending') { return ['pending'=>true]; }
            if ($row['status'] === 'ready') {
                $this->repo->update($id,['status'=>'active','updated_at'=>$this->repo->now()]);
                $this->repo->update($id,['outcome'=>'complete','completed_at'=>$this->repo->now()],true);
            } elseif ($row['status'] !== 'active') { return new ApiError(409,'reauth_required'); }
            return $this->response($row);
        });
    }
    private function response(array $row): array
    {
        $bundle=$this->cipher->decrypt($row);
        return ['access_token'=>$bundle['access_token'],'token_type'=>'Bearer','expires_at'=>$bundle['expires_at'],
            'generation'=>(int)$row['generation'],'granted_scope'=>$bundle['scope']];
    }
    public function refresh(string $id, string $secret, int $generation, string $reason): array
    {
        $result=$this->repo->transaction(function () use ($id,$secret,$generation,$reason) {
            $row=$this->authenticatedRow($id,$secret);
            $this->repo->rate('refresh',encodeId($id),60,6,$this->config['rate_limit_key']);
            if ($row['status'] === 'ready') {
                $session=$this->repo->session($id);
                if ($session['expires_at'] <= $this->repo->now()) { return $this->expire($row,$session); }
                return new ApiError(409,'reauth_required');
            }
            if ($failure=$this->usable($row)) { return $failure; }
            if ($row['status'] !== 'active') { return new ApiError(409,'reauth_required'); }
            if ($generation > (int)$row['generation']) { throw new ApiError(400,'invalid_request'); }
            if ($generation < (int)$row['generation'] || ($reason === 'expired' && (int)$row['access_expires_at'] > $this->repo->now()+60)) {
                return ['response'=>$this->response($row)];
            }
            $bundle=$this->cipher->decrypt($row);
            return ['operation'=>$this->reserve($row,'refreshing'),'refresh_token'=>$bundle['refresh_token']];
        });
        if (isset($result['response'])) { return $result['response']; }
        $this->execute($result['operation'],fn() => $this->provider->refresh($result['refresh_token']));
        return $this->repo->transaction(function () use ($id,$secret) {
            $row=$this->authenticatedRow($id,$secret);
            if ($failure=$this->usable($row)) { return $failure; }
            return $this->response($row);
        });
    }
    private function reserve(array $row, string $status): array
    {
        if ($row['operation_id'] !== null || $row['unresolved_operation_id'] !== null) { throw new ApiError(409,'reauth_required'); }
        $changes=['status'=>$status,'operation_id'=>random_bytes(16),'operation_deadline'=>$this->repo->now()+60,'updated_at'=>$this->repo->now()];
        $this->repo->update($row['link_id'],$changes);
        return array_replace($row,$changes);
    }
    private function recover(array $row): array
    {
        if ($row['operation_id'] === null || (int)$row['operation_deadline'] > $this->repo->now()) { return $row; }
        if ($row['status'] === 'revoking') {
            $changes=['status'=>'revoke_pending','operation_id'=>null,'operation_deadline'=>null,'next_revoke_at'=>$this->repo->now()+30];
        } else {
            $changes=['status'=>(int)$row['disconnect_requested'] === 1 ? 'revoke_pending' : 'reauth_required',
                'unresolved_operation_id'=>$row['operation_id'],'operation_id'=>null,'operation_deadline'=>null,
                'revocation_uncertain'=>1,'last_error_code'=>'operation_unknown','next_revoke_at'=>$this->repo->now()];
        }
        $changes['updated_at']=$this->repo->now(); $this->repo->update($row['link_id'],$changes);
        return array_replace($row,$changes);
    }
    private function execute(array $operation, callable $network): void
    {
        try { $bundle=tokenBundle($network(),$this->repo->now()); }
        catch (\Throwable $e) {
            $this->repo->transaction(function () use ($operation) {
                $row=$this->repo->link($operation['link_id']);
                if ($row['operation_id'] !== null && hash_equals($row['operation_id'],$operation['operation_id'])) {
                    $this->repo->update($row['link_id'],['status'=>(int)$row['disconnect_requested'] === 1 ? 'revoke_pending' : 'reauth_required',
                        'unresolved_operation_id'=>$row['operation_id'],'operation_id'=>null,'operation_deadline'=>null,
                        'revocation_uncertain'=>1,'last_error_code'=>'operation_unknown','next_revoke_at'=>$this->repo->now(),'updated_at'=>$this->repo->now()]);
                }
            });
            throw new ApiError(502,'provider_error');
        }
        // A failed DB commit must never lead to re-sending the old refresh token.
        $this->repo->transaction(function () use ($operation,$bundle) {
            $row=$this->recover($this->repo->link($operation['link_id']));
            $current=$row['operation_id'] !== null && hash_equals($row['operation_id'],$operation['operation_id']);
            $late=$row['unresolved_operation_id'] !== null && hash_equals($row['unresolved_operation_id'],$operation['operation_id']);
            if (!$current && !$late) { throw new ApiError(503,'service_unavailable'); }
            $quarantine=$late || (int)$row['disconnect_requested'] === 1;
            $changes=['generation'=>(int)$row['generation']+1,'token_key_id'=>$this->config['token_keys']['current'],
                'access_expires_at'=>$bundle['expires_at'],'granted_scope'=>$bundle['scope'],
                'status'=>$quarantine ? ($row['status'] === 'revoking' ? 'revoking' : 'revoke_pending') : ($operation['status'] === 'exchanging' ? 'ready' : 'active'),
                'unresolved_operation_id'=>null,'revocation_uncertain'=>0,'next_revoke_at'=>$quarantine ? $this->repo->now() : null,
                'last_error_code'=>null,'updated_at'=>$this->repo->now()];
            if ($current) { $changes+=['operation_id'=>null,'operation_deadline'=>null]; }
            // If a revoke is running, preserve its operation ID. Its completion detects this new generation.
            $changes['token_bundle']=$this->cipher->encrypt($bundle,array_replace($row,$changes));
            $this->repo->update($row['link_id'],$changes);
            if ($operation['status'] === 'exchanging') {
                $this->repo->session($row['link_id']);
                $this->repo->update($row['link_id'],['outcome'=>$quarantine ? 'failed' : 'ready'],true);
            }
        });
    }
    private function expire(array $row, array $session): ApiError
    {
        $this->repo->update($row['link_id'],['outcome'=>'expired','launch_token_hash'=>null],true);
        if ($row['status'] === 'ready') {
            $this->repo->update($row['link_id'],['status'=>'revoke_pending','disconnect_requested'=>1,
                'disconnected_at'=>$this->repo->now(),'next_revoke_at'=>$this->repo->now(),'updated_at'=>$this->repo->now()]);
        } elseif ($row['status'] === 'pending') {
            $this->repo->update($row['link_id'],['status'=>'revoked','disconnect_requested'=>1,
                'disconnected_at'=>$this->repo->now(),'revoked_at'=>$this->repo->now(),'updated_at'=>$this->repo->now()]);
        }
        return new ApiError(410,'link_expired');
    }
    public function disconnect(string $id, string $secret): array
    {
        $this->repo->transaction(function () use ($id,$secret) {
            $row=$this->authenticatedRow($id,$secret);
            $changes=['disconnect_requested'=>1,'disconnected_at'=>$row['disconnected_at'] ?? $this->repo->now(),'updated_at'=>$this->repo->now()];
            if ($row['operation_id'] === null && $row['status'] !== 'revoked') {
                $empty=$row['token_bundle'] === null && $row['unresolved_operation_id'] === null;
                $changes+=['status'=>$empty ? 'revoked' : 'revoke_pending','next_revoke_at'=>$this->repo->now()];
                if ($empty) { $changes['revoked_at']=$this->repo->now(); }
            }
            $this->repo->update($id,$changes);
        });
        $this->revoke($id);
        return $this->repo->transaction(function () use ($id) {
            $row=$this->repo->link($id);
            return ['disconnected'=>true,'revocation_pending'=>$row['status'] !== 'revoked',
                'provider_confirmation_required'=>(int)$row['revocation_uncertain'] === 1];
        });
    }
    public function revoke(string $id): void
    {
        $operation=$this->repo->transaction(function () use ($id) {
            $row=$this->recover($this->repo->link($id));
            if ($row['status'] !== 'revoke_pending' || $row['operation_id'] !== null || $row['token_bundle'] === null
                || ($row['next_revoke_at'] !== null && (int)$row['next_revoke_at'] > $this->repo->now())) { return null; }
            $token=$this->cipher->decrypt($row)['access_token'];
            $changes=['status'=>'revoking','operation_id'=>random_bytes(16),'operation_deadline'=>$this->repo->now()+60,'updated_at'=>$this->repo->now()];
            $this->repo->update($id,$changes);
            return ['row'=>array_replace($row,$changes),'token'=>$token];
        });
        if ($operation === null) { return; }
        try { $success=$this->provider->revoke($operation['token']); } catch (\Throwable $e) { $success=false; }
        $this->repo->transaction(function () use ($id,$operation,$success) {
            $row=$this->repo->link($id); $previous=$operation['row'];
            if ($row['operation_id'] === null || !hash_equals($row['operation_id'],$previous['operation_id'])) { return; }
            $same=(int)$row['generation'] === (int)$previous['generation'];
            $attempt=(int)$row['revoke_attempts']+1;
            $changes=['operation_id'=>null,'operation_deadline'=>null,'revoke_attempts'=>$attempt,
                'status'=>'revoke_pending','next_revoke_at'=>$this->repo->now()+min(21600,30*(2**min($attempt-1,10))),'updated_at'=>$this->repo->now()];
            if ($success && $same) {
                $changes+=['token_bundle'=>null,'token_key_id'=>null,'access_expires_at'=>null,'granted_scope'=>null];
                $changes['status']='revoked'; $changes['revoked_at']=$this->repo->now(); $changes['next_revoke_at']=null;
            } elseif ($success) { $changes['next_revoke_at']=$this->repo->now(); }
            $this->repo->update($id,$changes);
        });
    }
    public function cleanup(bool $network = true): void
    {
        $now=$this->repo->now();
        $ids=$this->repo->query("SELECT l.link_id FROM oauth_links l LEFT JOIN oauth_link_sessions s ON s.link_id=l.link_id WHERE
            (l.operation_deadline IS NOT NULL AND l.operation_deadline<=?) OR
            (l.status IN ('pending','ready') AND s.expires_at<=?) OR
            (l.disconnected_at IS NOT NULL AND l.disconnected_at<=? AND l.device_secret_hash IS NOT NULL)
            ORDER BY l.updated_at LIMIT 20",[$now,$now,$now-604800])->fetchAll();
        foreach ($ids as $entry) {
            $this->repo->transaction(function () use ($entry,$now) {
                $row=$this->recover($this->repo->link($entry['link_id']));
                if (in_array($row['status'],['pending','ready'],true)) {
                    $session=$this->repo->session($row['link_id']);
                    if ((int)$session['expires_at'] <= $now) { $this->expire($row,$session); }
                }
                if ($row['disconnected_at'] !== null && (int)$row['disconnected_at']+604800 <= $now) {
                    $this->repo->update($row['link_id'],['device_secret_hash'=>null]);
                    if ($row['status'] === 'revoked' && !(int)$row['revocation_uncertain'] && $row['unresolved_operation_id'] === null) {
                        $this->repo->query('DELETE FROM oauth_links WHERE link_id=?',[$row['link_id']]);
                    }
                }
            });
        }
        $this->repo->query('DELETE FROM oauth_rate_buckets WHERE expires_at<=? LIMIT 20',[$now]);
        if ($network) {
            $id=$this->repo->query("SELECT link_id FROM oauth_links WHERE status='revoke_pending' AND token_bundle IS NOT NULL AND next_revoke_at<=? ORDER BY next_revoke_at LIMIT 1",[$now])->fetchColumn();
            if (is_string($id)) { $this->revoke($id); }
        }
    }
}
