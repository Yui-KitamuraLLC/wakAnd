<?php
declare(strict_types=1);
namespace WakAnd\Auth;

final class ApiError extends \RuntimeException
{
    public function __construct(public readonly int $status, string $code)
    { parent::__construct($code); }
}

function encodeId(string $value): string
{ return rtrim(strtr(base64_encode($value), '+/', '-_'), '='); }

function decodeId(mixed $value, int $bytes): string
{
    if (!is_string($value) || !preg_match('/^[A-Za-z0-9_-]+$/D', $value)) {
        throw new ApiError(400, 'invalid_request');
    }
    $decoded = base64_decode(strtr($value, '-_', '+/'), true);
    if ($decoded === false || strlen($decoded) !== $bytes || encodeId($decoded) !== $value) {
        throw new ApiError(400, 'invalid_request');
    }
    return $decoded;
}

function tokenBundle(array $data, int $now): array
{
    foreach (['access_token', 'refresh_token', 'token_type', 'scope'] as $field) {
        if (!isset($data[$field]) || !is_string($data[$field]) || $data[$field] === '') {
            throw new ApiError(502, 'provider_error');
        }
    }
    if (strcasecmp($data['token_type'], 'Bearer') !== 0
        || !preg_match('/^[\x21-\x7e]{1,8192}$/D', $data['access_token'])
        || !preg_match('/^[\x21-\x7e]{1,8192}$/D', $data['refresh_token'])
        || strlen($data['scope']) > 1024 || !preg_match('/^[a-zA-Z0-9_. ,]+$/D', $data['scope'])) {
        throw new ApiError(502, 'provider_error');
    }
    $scope = preg_split('/[ ,]+/', $data['scope'], -1, PREG_SPLIT_NO_EMPTY);
    foreach (['read_stats.projects', 'write_heartbeats'] as $required) {
        if (!in_array($required, $scope, true)) { throw new ApiError(502, 'provider_error'); }
    }
    // Never substitute a documented lifetime for a missing response expiry.
    $expiry = null;
    if (isset($data['expires_in']) && preg_match('/^[0-9]{1,10}$/D', (string)$data['expires_in'])) {
        $expiry = $now + (int)$data['expires_in'];
    }
    if (isset($data['expires_at'])) {
        $value = $data['expires_at'];
        if (is_int($value) || (is_string($value) && preg_match('/^[0-9]{1,12}$/D', $value))) {
            $absolute = (int)$value;
        } elseif (is_string($value) && preg_match('/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/D', $value)) {
            try { $absolute = (new \DateTimeImmutable($value))->getTimestamp(); }
            catch (\Throwable $e) { throw new ApiError(502, 'provider_error'); }
        } else { throw new ApiError(502, 'provider_error'); }
        if ($expiry !== null && abs($expiry - $absolute) > 60) { throw new ApiError(502, 'provider_error'); }
        $expiry = $absolute;
    }
    if ($expiry === null || $expiry <= $now) { throw new ApiError(502, 'provider_error'); }
    sort($scope, SORT_STRING);
    return ['access_token' => $data['access_token'], 'refresh_token' => $data['refresh_token'],
        'token_type' => 'Bearer', 'scope' => implode(',', array_unique($scope)), 'expires_at' => $expiry];
}

final class TokenCipher
{
    public function __construct(private array $config) {}
    private function aad(array $row): string
    { return 'wakand|' . $this->config['environment'] . '|' . encodeId($row['link_id']) . '|' . $row['generation'] . '|' . $row['token_key_id']; }
    public function encrypt(array $bundle, array $row): string
    {
        $nonce = random_bytes(SODIUM_CRYPTO_AEAD_XCHACHA20POLY1305_IETF_NPUBBYTES);
        $key = $this->key($row['token_key_id']);
        return $nonce . sodium_crypto_aead_xchacha20poly1305_ietf_encrypt(json_encode($bundle, JSON_THROW_ON_ERROR), $this->aad($row), $nonce, $key);
    }
    private function key(string $id): string
    {
        $key = base64_decode($this->config['token_keys']['keys'][$id] ?? '', true);
        if ($key === false || strlen($key) !== 32) { throw new ApiError(503, 'service_unavailable'); }
        return $key;
    }
    public function decrypt(array $row): array
    {
        try {
            $blob = $row['token_bundle'];
            if (!is_string($blob) || strlen($blob) < 41) { throw new \RuntimeException(); }
            $plain = sodium_crypto_aead_xchacha20poly1305_ietf_decrypt(substr($blob, 24), $this->aad($row), substr($blob, 0, 24), $this->key($row['token_key_id']));
            if ($plain === false) { throw new \RuntimeException(); }
            $bundle = json_decode($plain, true, 8, JSON_THROW_ON_ERROR);
            if (!is_array($bundle) || ($bundle['expires_at'] ?? null) !== (int)$row['access_expires_at']
                || ($bundle['scope'] ?? null) !== $row['granted_scope']) { throw new \RuntimeException(); }
            return $bundle;
        } catch (\Throwable $e) { throw new ApiError(503, 'service_unavailable'); }
    }
}

interface Provider
{
    public function exchange(string $code): array;
    public function refresh(string $token): array;
    public function revoke(string $token): bool;
}

final class WakaTimeProvider implements Provider
{
    public function __construct(private array $config) {}
    private function post(string $path, array $data): array
    {
        $curl = curl_init('https://wakatime.com/oauth/' . $path);
        $body = '';
        curl_setopt_array($curl, [CURLOPT_POST => true, CURLOPT_POSTFIELDS => http_build_query($data + [
            'client_id' => $this->config['client_id'], 'client_secret' => $this->config['client_secret']], '', '&', PHP_QUERY_RFC3986),
            CURLOPT_HTTPHEADER => ['Accept: application/json', 'Content-Type: application/x-www-form-urlencoded'],
            CURLOPT_FOLLOWLOCATION => false, CURLOPT_PROTOCOLS => CURLPROTO_HTTPS,
            CURLOPT_SSL_VERIFYPEER => true, CURLOPT_SSL_VERIFYHOST => 2,
            CURLOPT_CONNECTTIMEOUT => 5, CURLOPT_TIMEOUT => 20,
            CURLOPT_WRITEFUNCTION => static function ($handle, string $chunk) use (&$body): int {
                if (strlen($body) + strlen($chunk) > 65536) { return 0; }
                $body .= $chunk; return strlen($chunk);
            }]);
        try {
            $ok = curl_exec($curl);
            $status = (int)curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
            if ($ok === false || $status !== 200) { throw new ApiError(502, 'provider_error'); }
            if ($path === 'revoke') { return []; }
            $result = json_decode($body, true, 16, JSON_THROW_ON_ERROR);
            if (!is_array($result)) { throw new ApiError(502, 'provider_error'); }
            return $result;
        } catch (\Throwable $e) { throw new ApiError(502, 'provider_error'); }
        finally { curl_close($curl); }
    }
    public function exchange(string $code): array
    { return $this->post('token', ['grant_type' => 'authorization_code', 'code' => $code, 'redirect_uri' => $this->config['redirect_uri']]); }
    public function refresh(string $token): array
    { return $this->post('token', ['grant_type' => 'refresh_token', 'refresh_token' => $token, 'redirect_uri' => $this->config['redirect_uri']]); }
    public function revoke(string $token): bool
    { $this->post('revoke', ['token' => $token]); return true; }
}
