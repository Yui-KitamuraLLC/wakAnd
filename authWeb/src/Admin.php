<?php
declare(strict_types=1);
namespace WakAnd\Auth;

/** Private FTP-created access file enables the browser tool for at most 30 minutes. */
function adminAccess(array $settings, int $now): array
{
    $access=$settings['admin_access'] ?? null;
    if (!is_array($access) || !is_string($access['key_sha256'] ?? null)
        || !preg_match('/^[a-f0-9]{64}$/D',$access['key_sha256'])
        || !is_int($access['created_at'] ?? null) || !is_int($access['expires_at'] ?? null)
        || $access['created_at'] > $now+120 || $access['expires_at'] <= $now
        || $access['expires_at']-$access['created_at'] > 1800 || $access['expires_at'] <= $access['created_at']) {
        throw new ApiError(404,'not_found');
    }
    return $access;
}
function authorizeAdmin(array $access, array $server): void
{
    if (($server['HTTP_ORIGIN'] ?? '') !== 'https://yui.eng.pro'
        || ($server['CONTENT_TYPE'] ?? '') !== 'application/json') { throw new ApiError(400,'invalid_request'); }
    $authorization=$server['HTTP_AUTHORIZATION'] ?? '';
    try {
        if (!str_starts_with($authorization,'Bearer ')) { throw new ApiError(401,'admin_unauthorized'); }
        $key=decodeId(substr($authorization,7),32);
        if (!hash_equals($access['key_sha256'],hash('sha256',$key))) { throw new ApiError(401,'admin_unauthorized'); }
    } catch (ApiError $e) { throw new ApiError(401,'admin_unauthorized'); }
}
function createPrivateKeys(string $path, string $webRoot, array $config): void
{
    $failure=new ApiError(409,'keys_not_created');
    $candidates=array_values($config['token_keys']['keys'] ?? []);
    $candidates[]=$config['rate_limit_key'] ?? '';
    foreach ($candidates as $candidate) {
        if (is_string($candidate) && $candidate !== '' && !str_starts_with($candidate,'REPLACE_')) { throw $failure; }
    }
    $directory=realpath(dirname($path)); $public=realpath($webRoot);
    if ($directory === false || $public === false || $directory === $public
        || str_starts_with($directory,rtrim($public,DIRECTORY_SEPARATOR).DIRECTORY_SEPARATOR) || !is_writable($directory)) {
        throw new ApiError(503,'private_storage_unavailable');
    }
    $target=$directory . DIRECTORY_SEPARATOR . basename($path);
    $content='<?php return ' . var_export(['token_keys'=>['current'=>'v1','keys'=>['v1'=>base64_encode(random_bytes(32))]],
        'rate_limit_key'=>base64_encode(random_bytes(32))],true) . ';' . PHP_EOL;
    $mask=umask(0077); $file=false;
    try {
        $file=@fopen($target,'x'); // Refuse existing files and symlinks; never rotate a key here.
        if ($file === false) { throw $failure; }
        if (fwrite($file,$content) !== strlen($content) || !fflush($file) || !@chmod($target,0600)) {
            fclose($file); $file=false; @unlink($target);
            throw new ApiError(503,'private_storage_unavailable');
        }
    } finally { if (is_resource($file)) { fclose($file); } umask($mask); }
}
function mysqlTlsFromGreeting(string $payload): bool
{
    if ($payload === '' || ord($payload[0]) !== 10) { throw new ApiError(503,'service_unavailable'); }
    $versionEnd=strpos($payload,"\0",1);
    if ($versionEnd === false || $versionEnd > 128 || strlen($payload)<$versionEnd+16) {
        throw new ApiError(503,'service_unavailable');
    }
    // Protocol::HandshakeV10: version NUL, thread(4), scramble(8), filler(1), capability_flags_1(2).
    $flags=unpack('vflags',substr($payload,$versionEnd+14,2));
    return ($flags['flags'] & 2048) !== 0; // CLIENT_SSL. This is capability, not verified encryption.
}
function probeDatabaseTls(): ?bool
{
    $socket=false;
    try {
        $deadline=microtime(true)+5;
        $socket=@stream_socket_client('tcp://mysql50009.db.sakura.ne.jp:3306',$errorCode,$errorMessage,3);
        if ($socket === false) { return null; }
        stream_set_timeout($socket,2);
        $read=static function (int $length) use ($socket,$deadline): string {
            $bytes='';
            while (strlen($bytes)<$length && microtime(true)<$deadline) {
                $chunk=@fread($socket,$length-strlen($bytes));
                if ($chunk === false || $chunk === '') { break; }
                $bytes.=$chunk;
            }
            if (strlen($bytes)!==$length) { throw new ApiError(503,'service_unavailable'); }
            return $bytes;
        };
        $header=$read(4);
        $length=ord($header[0]) | (ord($header[1])<<8) | (ord($header[2])<<16);
        if ($length<16 || $length>8192 || ord($header[3])!==0) { return null; }
        // Read the server greeting, then close. Never send username/password or any client packet.
        return mysqlTlsFromGreeting($read($length));
    } catch (\Throwable $e) { return null; }
    finally { if (is_resource($socket)) { fclose($socket); } }
}
/** Read-only flags; diagnostics never perform OAuth requests, DDL or cleanup. */
function inspectRuntime(callable $load, array $checksums): array
{
    $result=['extensions'=>['pdo_mysql'=>extension_loaded('pdo_mysql'),'curl'=>extension_loaded('curl'),'sodium'=>extension_loaded('sodium')],
        'configuration'=>false,'database'=>false,'schema'=>false,'tls_encrypted'=>false,'tls_mode'=>null,'maintenance'=>true,
        'operations_in_progress'=>null,'operations_expired'=>null,'revocation_pending'=>null,'database_tls_advertised'=>null,
        'sodium_module_available'=>is_readable(rtrim((string)ini_get('extension_dir'),DIRECTORY_SEPARATOR).DIRECTORY_SEPARATOR.'sodium.so'),
        'crypto'=>tokenCryptoStatus()];
    try {
        $config=$load(); $result['maintenance']=($config['maintenance'] ?? true) !== false;
        if (($config['database']['host'] ?? '')==='mysql50009.db.sakura.ne.jp') { $result['database_tls_advertised']=probeDatabaseTls(); }
        $config['maintenance']=false; // This does not edit settings or enable ordinary endpoints.
        $config=runtimeConfig($config); $result['configuration']=true; $result['tls_mode']=$config['database']['tls_mode'];
        // Read-only DB/schema checks do not decrypt tokens or contact the OAuth provider.
        // Report independently of curl/crypto; ordinary endpoints still require both plus pdo_mysql.
        if (!$result['extensions']['pdo_mysql']) { return $result; }
        $db=connectDatabase($config); $result['database']=true;
        $ssl=$db->query("SHOW SESSION STATUS LIKE 'Ssl_cipher'")->fetch(); $result['tls_encrypted']=($ssl['Value'] ?? '') !== '';
        $repo=new Repository($db,$checksums); $result['schema']=true;
        $result['operations_in_progress']=(int)$repo->query('SELECT COUNT(*) FROM oauth_links WHERE operation_id IS NOT NULL AND operation_deadline>UNIX_TIMESTAMP()')->fetchColumn();
        $result['operations_expired']=(int)$repo->query('SELECT COUNT(*) FROM oauth_links WHERE operation_id IS NOT NULL AND operation_deadline<=UNIX_TIMESTAMP()')->fetchColumn();
        $result['revocation_pending']=(int)$repo->query("SELECT COUNT(*) FROM oauth_links WHERE status IN ('revoke_pending','revoking') OR revocation_uncertain=1")->fetchColumn();
    } catch (\Throwable $e) { /* Never return exception text, settings, query parameters or driver errors. */ }
    return $result;
}
function adminPage(string $nonce): string
{
    return '<!doctype html><html lang="ja"><meta charset="utf-8"><meta name="viewport" content="width=device-width">'
        . '<title>wakAnd 管理者確認</title><h1>wakAnd 管理者確認</h1><p>管理用アクセスキーを入力してください。鍵やパスワードは結果に表示されません。</p>'
        . '<form id="admin"><label>管理用アクセスキー <input id="key" type="password" autocomplete="off" required></label>'
        . '<p><button type="submit" value="check">設定・DB接続を確認</button> '
        . '<button type="submit" value="generate_keys">非公開領域へ初期鍵を生成</button> '
        . '<button type="submit" value="maintain">保留中の失効・期限切れを処理</button></p></form>'
        . '<pre id="result" aria-live="polite"></pre><script nonce="' . $nonce . '">'
        . 'document.getElementById("admin").addEventListener("submit",async e=>{e.preventDefault();'
        . 'const action=e.submitter.value;if(action==="maintain"&&!confirm("保留中の連携失効と期限切れの処理を実行しますか？"))return;'
        . 'if(action==="generate_keys"&&!confirm("非公開領域へ初期鍵を生成しますか？既存鍵は置換されません。"))return;'
        . 'const out=document.getElementById("result"),input=document.getElementById("key");out.textContent="確認中…";'
        . 'const key=input.value.trim();input.value="";try{const response=await fetch("/android/wakAnd/admin.php",{method:"POST",'
        . 'redirect:"error",cache:"no-store",credentials:"omit",headers:{"Content-Type":"application/json","Authorization":"Bearer "+key},'
        . 'body:JSON.stringify({action})});out.textContent=JSON.stringify(await response.json(),null,2);}catch(error){out.textContent="接続を確認できませんでした。";}});'
        . '</script></html>';
}
function handleAdmin(): void
{
    ini_set('display_errors','0'); ini_set('log_errors','0');
    header('Content-Type: application/json; charset=utf-8'); header('Cache-Control: no-store');
    header('X-Content-Type-Options: nosniff'); header('Referrer-Policy: no-referrer');
    $nonce=base64_encode(random_bytes(18));
    header("Content-Security-Policy: default-src 'none'; script-src 'nonce-$nonce'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
    try {
        if (($_SERVER['HTTP_HOST'] ?? '') !== 'yui.eng.pro' || ($_SERVER['HTTPS'] ?? '') !== 'on'
            || ($_SERVER['QUERY_STRING'] ?? '') !== '') { throw new ApiError(400,'invalid_request'); }
        $private='/home/yui-kitamura/.config/wakand/'; $public='/home/yui-kitamura/www';
        try { $access=adminAccess(readPrivateSettings($private.'admin-access.php',$public),time()); }
        catch (\Throwable $e) { throw new ApiError(404,'not_found'); }
        $method=$_SERVER['REQUEST_METHOD'] ?? '';
        if ($method === 'GET') { header('Content-Type: text/html; charset=utf-8'); echo adminPage($nonce); return; }
        if ($method !== 'POST') { header('Allow: GET, POST'); throw new ApiError(405,'method_not_allowed'); }
        authorizeAdmin($access,$_SERVER);
        if ((int)($_SERVER['CONTENT_LENGTH'] ?? 0)>512) { throw new ApiError(400,'invalid_request'); }
        $raw=file_get_contents('php://input',false,null,0,513);
        if ($raw === false || strlen($raw)>512) { throw new ApiError(400,'invalid_request'); }
        $body=parseBody($raw,['action']);
        if ($body['action'] === 'check') {
            $result=inspectRuntime(fn()=>loadConfig($private.'oauth.php',$public),require __DIR__ . '/_schema.php');
        } elseif ($body['action'] === 'generate_keys') {
            createPrivateKeys($private.'generated-keys.php',$public,loadConfig($private.'oauth.php',$public));
            $result=['keys_created'=>true];
        } elseif ($body['action'] === 'maintain') {
            $config=loadConfig($private.'oauth.php',$public); $config['maintenance']=false; $config=runtimeConfig($config);
            $repo=new Repository(connectDatabase($config),require __DIR__ . '/_schema.php');
            (new OAuthService($repo,new WakaTimeProvider($config),$config))->cleanup();
            $result=['maintenance_pass_completed'=>true,
                'pending_count'=>(int)$repo->query("SELECT COUNT(*) FROM oauth_links WHERE status IN ('revoke_pending','revoking') OR revocation_uncertain=1")->fetchColumn()];
        } else { throw new ApiError(400,'invalid_request'); }
        echo json_encode($result,JSON_THROW_ON_ERROR);
    } catch (\Throwable $e) {
        http_response_code($e instanceof ApiError ? $e->status : 503);
        echo json_encode(['error'=>$e instanceof ApiError ? $e->getMessage() : 'service_unavailable']);
    }
}
