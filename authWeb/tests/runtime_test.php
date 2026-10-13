<?php
declare(strict_types=1);
foreach (['Config','Core','Database','OAuthService','Http'] as $name) { require __DIR__ . '/../src/' . $name . '.php'; }
use WakAnd\Auth\ApiError;
use WakAnd\Auth\OAuthService;
use WakAnd\Auth\Repository;
use WakAnd\Auth\Provider;
use WakAnd\Auth\TokenCipher;
use function WakAnd\Auth\encodeId;
use function WakAnd\Auth\decodeId;
use function WakAnd\Auth\parseQuery;
use function WakAnd\Auth\parseBody;
use function WakAnd\Auth\tokenBundle;

function check(bool $ok, string $label): void { if (!$ok) { throw new LogicException($label); } }
function rejected(callable $work, string $code): void {
    try { $work(); } catch (ApiError $e) { check($e->getMessage()===$code,'wrong error classification'); return; }
    throw new LogicException('expected rejection');
}
function db(): PDO {
    return new PDO('mysql:host=127.0.0.1;port=3306;dbname=wakand_test;charset=utf8mb4','root','disposable-ci-password',[
        PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION,PDO::ATTR_EMULATE_PREPARES=>false,PDO::ATTR_DEFAULT_FETCH_MODE=>PDO::FETCH_ASSOC]);
}
final class FakeProvider implements Provider {
    public int $exchanges=0; public int $refreshes=0; public int $revokes=0;
    public $duringExchange=null; public $duringRefresh=null; public $duringRevoke=null;
    public bool $failRefresh=false; public bool $failRevoke=false;
    public function bundle(): array { return ['access_token'=>'fake-access-'.$this->refreshes,'refresh_token'=>'fake-refresh-'.$this->refreshes,
        'token_type'=>'Bearer','expires_in'=>3600,'scope'=>OAuthService::SCOPE]; }
    public function exchange(string $code): array { $this->exchanges++; if ($this->duringExchange) { ($this->duringExchange)(); } return $this->bundle(); }
    public function refresh(string $token): array { $this->refreshes++; if ($this->duringRefresh) { ($this->duringRefresh)(); } if ($this->failRefresh) { throw new RuntimeException('fake-secret'); } return $this->bundle(); }
    public function revoke(string $token): bool { $this->revokes++; if ($this->duringRevoke) { ($this->duringRevoke)(); } if ($this->failRevoke) { throw new RuntimeException('fake-secret'); } return true; }
}
$config=['client_id'=>'test','client_secret'=>'fake-secret','redirect_uri'=>OAuthService::BASE.'oauth/callback','environment'=>'production',
    'token_keys'=>['current'=>'v1','keys'=>['v1'=>base64_encode(random_bytes(32))]],'rate_limit_key'=>base64_encode(random_bytes(32))];
$ddl=file_get_contents(__DIR__.'/../schema/001_oauth_mysql.sql'); $checksum=hash('sha256',$ddl);
$pdo=db();
// This script uses only the dedicated, disposable CI DB. Never uses production settings.
$pdo->prepare('INSERT INTO oauth_schema_versions (version,checksum_sha256,applied_at) VALUES (1,UNHEX(?),UNIX_TIMESTAMP())')->execute([$checksum]);
$repo=new Repository($pdo,[$checksum]); $provider=new FakeProvider(); $service=new OAuthService($repo,$provider,$config);
$otherRepo=new Repository(db(),[$checksum]); $other=new OAuthService($otherRepo,$provider,$config);
function fresh(): array {
    global $repo,$service,$provider;
    $repo->query('DELETE FROM oauth_link_sessions'); $repo->query('DELETE FROM oauth_links'); $repo->query('DELETE FROM oauth_rate_buckets');
    $provider->exchanges=0; $provider->refreshes=0; $provider->revokes=0;
    $provider->duringExchange=null; $provider->duringRefresh=null; $provider->duringRevoke=null; $provider->failRefresh=false; $provider->failRevoke=false;
    $secret=random_bytes(32); $verifier=random_bytes(32);
    $start=$service->start(hash('sha256',$secret,true),hash('sha256',encodeId($verifier),true),'127.0.0.1');
    $id=decodeId($start['link_id'],16); parse_str(parse_url($start['browser_url'],PHP_URL_QUERY),$launch);
    $browser=$service->launch($id,decodeId($launch['launch_token'],32));
    parse_str(parse_url($browser['url'],PHP_URL_QUERY),$auth);
    return [$id,$secret,$verifier,decodeId($auth['state'],32),[OAuthService::cookieName($id)=>$browser['cookie']]];
}
function active(): array {
    global $service;
    [$id,$secret,$verifier,$state,$cookies]=fresh();
    $service->callback($state,'fake-code',null,$cookies); $service->complete($id,$secret,$verifier);
    return [$id,$secret,$verifier];
}

rejected(fn()=>new Repository(db(),[str_repeat('0',64)]),'service_unavailable');
rejected(fn()=>decodeId('a',32),'invalid_request');
rejected(fn()=>parseQuery('state=a&state=b',['state']),'invalid_request');
rejected(fn()=>parseQuery('state[]=a',['state']),'invalid_request');
rejected(fn()=>parseBody('{"verifier":"a","verifi\\u0065r":"b"}',['verifier']),'invalid_request');
rejected(fn()=>parseBody('{"verifier":"a","unknown":1}',['verifier']),'invalid_request');
rejected(fn()=>parseBody('[]',[]),'invalid_request');
rejected(fn()=>tokenBundle(['access_token'=>'fake'],time()),'provider_error');
check(tokenBundle($provider->bundle(),1000)['expires_at']===4600,'response expiry');
$cipher=new TokenCipher($config);
$row=['link_id'=>random_bytes(16),'generation'=>1,'token_key_id'=>'v1','access_expires_at'=>4600,'granted_scope'=>OAuthService::SCOPE];
$bundle=tokenBundle($provider->bundle(),1000); $row['granted_scope']=$bundle['scope']; $row['token_bundle']=$cipher->encrypt($bundle,$row);
check($cipher->decrypt($row)===$bundle,'cipher round trip');
foreach (['link_id'=>random_bytes(16),'generation'=>2,'token_key_id'=>'missing','access_expires_at'=>4601,'granted_scope'=>'email'] as $field=>$value) {
    $bad=array_replace($row,[$field=>$value]); rejected(fn()=>$cipher->decrypt($bad),'service_unavailable');
}
$otherCipher=new TokenCipher(array_replace($config,['environment'=>'test'])); rejected(fn()=>$otherCipher->decrypt($row),'service_unavailable');

[$id,$secret,$verifier,$state,$cookies]=fresh();
rejected(fn()=>$service->callback($state,'fake-code',null,[]),'invalid_request');
check($provider->exchanges===0,'missing cookie prevented exchange');
rejected(fn()=>$service->complete($id,$secret,random_bytes(32)),'device_unauthorized');
$provider->duringExchange=function () use ($other,$state,$cookies,$id) {
    rejected(fn()=>$other->callback($state,'fake-code',null,$cookies),'invalid_request');
};
$service->callback($state,'fake-code',null,$cookies);
check($provider->exchanges===1,'one callback exchange across independent connections');
$first=$service->complete($id,$secret,$verifier); $repeat=$other->complete($id,$secret,$verifier);
check($first===$repeat && $first['generation']===1,'lost complete response recovered same generation');
check(!isset($first['refresh_token']),'refresh never returned to device');
rejected(fn()=>$service->authenticate($id,random_bytes(32),'127.0.0.1'),'device_unauthorized');

$provider->duringRefresh=function () use ($other,$id,$secret) { rejected(fn()=>$other->refresh($id,$secret,1,'unauthorized'),'operation_in_progress'); };
$second=$service->refresh($id,$secret,1,'unauthorized');
check($provider->refreshes===1 && $second['generation']===2,'single refresh across independent connections');
check($other->refresh($id,$secret,1,'unauthorized')===$second && $provider->refreshes===1,'stale generation fetch without refresh');
rejected(fn()=>$service->refresh($id,$secret,3,'unauthorized'),'invalid_request');

[$id,$secret]=active();
$provider->duringRefresh=function () use ($other,$id,$secret) { $other->disconnect($id,$secret); };
rejected(fn()=>$service->refresh($id,$secret,1,'unauthorized'),'link_disconnected');
$stored=$repo->query('SELECT * FROM oauth_links WHERE link_id=?',[$id])->fetch();
check($stored['status']==='revoke_pending' && (int)$stored['generation']===2,'disconnect during update never returns active');
$service->revoke($id); $stored=$repo->query('SELECT * FROM oauth_links WHERE link_id=?',[$id])->fetch();
check($stored['status']==='revoked' && $stored['token_bundle']===null,'revocation removes ciphertext');
check(!$service->disconnect($id,$secret)['revocation_pending'],'disconnect retry idempotent');

[$id,$secret]=active(); $provider->failRefresh=true;
rejected(fn()=>$service->refresh($id,$secret,1,'unauthorized'),'provider_error');
rejected(fn()=>$service->refresh($id,$secret,1,'unauthorized'),'reauth_required');
check($provider->refreshes===1,'unknown refresh never resends old refresh token');
$result=$service->disconnect($id,$secret);
check($result['provider_confirmation_required'] && !$result['revocation_pending'],'old token revocation does not prove unknown token revoked');

[$id,$secret]=active();
$provider->duringRefresh=function () use ($repo,$other,$id,$secret) {
    $repo->query('UPDATE oauth_links SET operation_deadline=UNIX_TIMESTAMP()-1 WHERE link_id=?',[$id]);
    rejected(fn()=>$other->refresh($id,$secret,1,'unauthorized'),'reauth_required');
    $other->disconnect($id,$secret); // Old token revoked before delayed new token arrives.
};
rejected(fn()=>$service->refresh($id,$secret,1,'unauthorized'),'link_disconnected');
$stored=$repo->query('SELECT * FROM oauth_links WHERE link_id=?',[$id])->fetch();
check($stored['status']==='revoke_pending' && $stored['token_bundle']!==null && (int)$stored['generation']===2,'late success recovered after old revocation');
$provider->duringRefresh=null; $service->revoke($id);
check($provider->revokes===2,'both old and delayed tokens revoked');

[$id,$secret,$verifier,$state,$cookies]=fresh(); $service->callback($state,'fake-code',null,$cookies);
$repo->query('UPDATE oauth_link_sessions SET expires_at=UNIX_TIMESTAMP()-1 WHERE link_id=?',[$id]);
rejected(fn()=>$service->complete($id,$secret,$verifier),'link_expired');
check($repo->query('SELECT status FROM oauth_links WHERE link_id=?',[$id])->fetchColumn()==='revoke_pending','unreceived ready token expires into revocation');
$service->cleanup(); check($provider->revokes===1,'bounded cleanup revokes abandoned token');

[$id,$secret]=active(); $provider->failRevoke=true;
check($service->disconnect($id,$secret)['revocation_pending'],'failed revoke remains pending');
$stored=$repo->query('SELECT * FROM oauth_links WHERE link_id=?',[$id])->fetch();
check($stored['token_bundle']!==null && (int)$stored['next_revoke_at']>$repo->now(),'backoff retains encrypted token');
$repo->query('UPDATE oauth_links SET disconnected_at=UNIX_TIMESTAMP()-604801 WHERE link_id=?',[$id]); $service->cleanup(false);
rejected(fn()=>$service->authenticate($id,$secret,'127.0.0.1'),'device_unauthorized');
check($repo->query('SELECT token_bundle FROM oauth_links WHERE link_id=?',[$id])->fetchColumn()!==null,'retention never deletes failed revocation token');

fresh();
for ($i=0;$i<9;$i++) { $service->start(random_bytes(32),random_bytes(32),'127.0.0.1'); }
rejected(fn()=>$service->start(random_bytes(32),random_bytes(32),'127.0.0.1'),'rate_limited');
check((int)$repo->query('SELECT COUNT(*) FROM oauth_links')->fetchColumn()===10,'rate limit precedes link creation');
echo "OAuth runtime: parsing, encryption, sessions, independent-connection races, lost responses, timeout quarantine, late success, revocation, retention and rate limits passed.\n";
