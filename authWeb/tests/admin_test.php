<?php
declare(strict_types=1);
foreach (['Config','Core','Database','OAuthService','Http','Admin'] as $library) { require __DIR__.'/../src/'.$library.'.php'; }
use WakAnd\Auth\ApiError;
use function WakAnd\Auth\adminAccess;
use function WakAnd\Auth\authorizeAdmin;
use function WakAnd\Auth\createPrivateKeys;
use function WakAnd\Auth\inspectRuntime;
use function WakAnd\Auth\encodeId;
function verify(bool $value): void { if (!$value) { throw new LogicException('Admin security check failed'); } }
function rejects(callable $operation, string $code): void {
    try { $operation(); }
    catch (ApiError $e) { verify($e->getMessage()===$code); return; }
    throw new LogicException('Expected admin rejection');
}
$key=random_bytes(32); $now=time();
$settings=['admin_access'=>['key_sha256'=>hash('sha256',$key),'created_at'=>$now,'expires_at'=>$now+1800]];
$access=adminAccess($settings,$now);
$server=['HTTP_ORIGIN'=>'https://yui.eng.pro','CONTENT_TYPE'=>'application/json','HTTP_AUTHORIZATION'=>'Bearer '.encodeId($key)];
authorizeAdmin($access,$server);
rejects(fn()=>adminAccess([],$now),'not_found');
rejects(fn()=>adminAccess($settings,$now+1800),'not_found');
$tooLong=$settings; $tooLong['admin_access']['expires_at']=$now+1801;
rejects(fn()=>adminAccess($tooLong,$now),'not_found');
rejects(fn()=>authorizeAdmin($access,array_replace($server,['HTTP_ORIGIN'=>'https://evil.example'])),'invalid_request');
rejects(fn()=>authorizeAdmin($access,array_diff_key($server,['HTTP_ORIGIN'=>true])),'invalid_request');
rejects(fn()=>authorizeAdmin($access,array_replace($server,['CONTENT_TYPE'=>'application/x-www-form-urlencoded'])),'invalid_request');
rejects(fn()=>authorizeAdmin($access,array_replace($server,['HTTP_AUTHORIZATION'=>'Bearer '.encodeId(random_bytes(32))])),'admin_unauthorized');
rejects(fn()=>authorizeAdmin($access,array_diff_key($server,['HTTP_AUTHORIZATION'=>true])),'admin_unauthorized');
ob_start();
$flags=inspectRuntime(static function (): array { throw new RuntimeException('FAKE_SECRET_NEVER_RETURN'); },[]);
$output=ob_get_clean(); verify($output==='' && !str_contains(json_encode($flags),'FAKE_SECRET'));
$greeting="\x0a8.0-test\0" . pack('V',1) . 'abcdefgh' . "\0";
verify(\WakAnd\Auth\mysqlTlsFromGreeting($greeting.pack('v',2048))===true);
verify(\WakAnd\Auth\mysqlTlsFromGreeting($greeting.pack('v',512))===false);
rejects(fn()=>\WakAnd\Auth\mysqlTlsFromGreeting("\xff"),'service_unavailable');
rejects(fn()=>\WakAnd\Auth\mysqlTlsFromGreeting("\x0a8.0\0"),'service_unavailable');

$root=sys_get_temp_dir().'/wakand-admin-test-'.bin2hex(random_bytes(8)); mkdir($root,0700); mkdir($root.'/www');
$target=$root.'/generated-keys.php';
try {
    ob_start(); createPrivateKeys($target,$root.'/www',[]); verify(ob_get_clean()==='');
    $values=require $target;
    verify(strlen(base64_decode($values['token_keys']['keys']['v1'],true))===32);
    verify(strlen(base64_decode($values['rate_limit_key'],true))===32);
    verify($values['rate_limit_key']!==$values['token_keys']['keys']['v1']);
    verify((fileperms($target)&0777)===0600);
    $before=hash_file('sha256',$target);
    rejects(fn()=>createPrivateKeys($target,$root.'/www',[]),'keys_not_created');
    verify(hash_file('sha256',$target)===$before);
    rejects(fn()=>createPrivateKeys($root.'/second.php',$root.'/www',$values),'keys_not_created');
    rejects(fn()=>createPrivateKeys($root.'/www/exposed.php',$root.'/www',[]),'private_storage_unavailable');
    verify(!file_exists($root.'/www/exposed.php'));
    symlink($target,$root.'/alias.php');
    rejects(fn()=>createPrivateKeys($root.'/alias.php',$root.'/www',[]),'keys_not_created');
    verify(hash_file('sha256',$target)===$before);
    $private=$root.'/access.php'; file_put_contents($private,'<?php echo "FAKE_SECRET"; return [];');
    ob_start();
    try { \WakAnd\Auth\readPrivateSettings($private,$root.'/www'); throw new LogicException('Expected rejection'); }
    catch (RuntimeException $e) { verify(!str_contains($e->getMessage(),'FAKE_SECRET')); }
    verify(ob_get_clean()==='');
} finally {
    foreach ([$target,$root.'/alias.php',$root.'/access.php'] as $file) { if (is_file($file) || is_link($file)) { unlink($file); } }
    rmdir($root.'/www'); rmdir($root);
}
$_SERVER=['HTTP_HOST'=>'yui.eng.pro','HTTPS'=>'on','QUERY_STRING'=>'','REQUEST_METHOD'=>'GET'];
ob_start(); \WakAnd\Auth\handleAdmin(); $body=ob_get_clean();
verify(http_response_code()===404 && json_decode($body,true)===['error'=>'not_found']);
echo "Browser admin: expiry, authorization, same-origin JSON, disabled default, secret redaction, private keys and overwrite/symlink refusal passed.\n";
