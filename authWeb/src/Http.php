<?php
declare(strict_types=1);
namespace WakAnd\Auth;

function parseQuery(string $raw, array $allowed): array
{
    $values=[];
    foreach ($raw === '' ? [] : explode('&',$raw) as $pair) {
        $parts=explode('=',$pair,2); $key=urldecode($parts[0]); $value=urldecode($parts[1] ?? '');
        if (!in_array($key,$allowed,true) || array_key_exists($key,$values) || strlen($value)>8192) { throw new ApiError(400,'invalid_request'); }
        $values[$key]=$value;
    }
    return $values;
}
function parseBody(string $raw, array $fields): array
{
    if (strlen($raw)>8192) { throw new ApiError(400,'invalid_request'); }
    try { $value=json_decode($raw,false,4,JSON_THROW_ON_ERROR); }
    catch (\Throwable $e) { throw new ApiError(400,'invalid_request'); }
    if (!$value instanceof \stdClass) { throw new ApiError(400,'invalid_request'); }
    $data=(array)$value;
    if (array_diff(array_keys($data),$fields) || array_diff($fields,array_keys($data))) { throw new ApiError(400,'invalid_request'); }
    // Contracts are flat objects. Reject duplicate keys including escaped spellings.
    preg_match_all('/"((?:[^"\\\\]|\\\\.)*)"\s*:/',$raw,$matches);
    $keys=array_map(static fn($key) => json_decode('"' . $key . '"',true,2,JSON_THROW_ON_ERROR),$matches[1]);
    if (count($keys)!==count(array_unique($keys))) { throw new ApiError(400,'invalid_request'); }
    return $data;
}
function makeService(): OAuthService
{
    foreach (['pdo_mysql','curl'] as $extension) {
        if (!extension_loaded($extension)) { throw new ApiError(503,'service_unavailable'); }
    }
    requireTokenCrypto();
    $config=runtimeConfig(loadConfig('/home/yui-kitamura/.config/wakand/oauth.php','/home/yui-kitamura/www'));
    $checksums=require __DIR__ . '/_schema.php';
    return new OAuthService(new Repository(connectDatabase($config),$checksums),new WakaTimeProvider($config),$config);
}
function handleHttp(): void
{
    ini_set('display_errors','0'); ini_set('log_errors','0');
    $started=microtime(true); $service=null; $requestId=bin2hex(random_bytes(8));
    header('Content-Type: application/json; charset=utf-8'); header('Cache-Control: no-store');
    header('X-Content-Type-Options: nosniff'); header('Referrer-Policy: no-referrer');
    header("Content-Security-Policy: default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
    try {
        if (($_SERVER['HTTP_HOST'] ?? '') !== 'yui.eng.pro' || ($_SERVER['HTTPS'] ?? '') !== 'on') { throw new ApiError(400,'invalid_request'); }
        $path=parse_url($_SERVER['REQUEST_URI'] ?? '',PHP_URL_PATH);
        $method=$_SERVER['REQUEST_METHOD'] ?? '';
        $gets=['/android/wakAnd/oauth/launch','/android/wakAnd/oauth/callback','/android/wakAnd/app/complete'];
        $posts=['/android/wakAnd/v1/link/start','/android/wakAnd/v1/link/complete','/android/wakAnd/v1/token/refresh','/android/wakAnd/v1/link/disconnect'];
        if (!in_array($path,[...$gets,...$posts],true)) { throw new ApiError(404,'not_found'); }
        $expected=in_array($path,$gets,true) ? 'GET' : 'POST';
        if ($method !== $expected) { header('Allow: ' . $expected); throw new ApiError(405,'method_not_allowed'); }
        if ($path === '/android/wakAnd/app/complete') {
            $query=parseQuery($_SERVER['QUERY_STRING'] ?? '',['link_id']); decodeId($query['link_id'] ?? null,16);
            header('Content-Type: text/html; charset=utf-8');
            echo '<!doctype html><html lang="ja"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>wakAnd</title><p>wakAndアプリに戻り、連携状態を確認してください。</p><p>Return to wakAnd to check your connection.</p></html>';
            return;
        }
        $service=makeService();
        if ($expected === 'GET') {
            if ($path === '/android/wakAnd/oauth/launch') {
                $query=parseQuery($_SERVER['QUERY_STRING'] ?? '',['link_id','launch_token']);
                $id=decodeId($query['link_id'] ?? null,16);
                $result=$service->launch($id,decodeId($query['launch_token'] ?? null,32));
                setcookie(OAuthService::cookieName($id),$result['cookie'],['expires'=>time()+600,'path'=>'/android/wakAnd/oauth/','secure'=>true,'httponly'=>true,'samesite'=>'Lax']);
                header('Location: ' . $result['url'],true,303); return;
            }
            $query=parseQuery($_SERVER['QUERY_STRING'] ?? '',['code','state','error']);
            if (isset($query['code']) === isset($query['error']) || ($query['code'] ?? $query['error'] ?? '') === '') { throw new ApiError(400,'invalid_request'); }
            $url=$service->callback(decodeId($query['state'] ?? null,32),$query['code'] ?? null,$query['error'] ?? null,$_COOKIE);
            header('Location: ' . $url,true,303); return;
        }
        if (($_SERVER['QUERY_STRING'] ?? '') !== '' || ($_SERVER['CONTENT_TYPE'] ?? '') !== 'application/json') { throw new ApiError(400,'invalid_request'); }
        $raw=file_get_contents('php://input',false,null,0,8193);
        if ($raw === false || (isset($_SERVER['CONTENT_LENGTH']) && (int)$_SERVER['CONTENT_LENGTH']>8192)) { throw new ApiError(400,'invalid_request'); }
        $ip=$_SERVER['REMOTE_ADDR'] ?? '';
        if ($path === '/android/wakAnd/v1/link/start') {
            $body=parseBody($raw,['device_secret_hash','verifier_challenge']);
            $result=$service->start(decodeId($body['device_secret_hash'],32),decodeId($body['verifier_challenge'],32),$ip);
            http_response_code(201);
        } else {
            try {
                $id=decodeId($_SERVER['HTTP_X_WAKAND_LINK_ID'] ?? null,16);
                $auth=$_SERVER['HTTP_AUTHORIZATION'] ?? '';
                if (!str_starts_with($auth,'Bearer ')) { throw new ApiError(401,'device_unauthorized'); }
                $secret=decodeId(substr($auth,7),32);
            } catch (ApiError $e) {
                $service->authenticationFailure($ip);
                throw new ApiError(401,'device_unauthorized');
            }
            $service->authenticate($id,$secret,$ip);
            if ($path === '/android/wakAnd/v1/link/complete') {
                $body=parseBody($raw,['verifier']); $result=$service->complete($id,$secret,decodeId($body['verifier'],32));
                if (isset($result['pending'])) { http_response_code(202); }
            } elseif ($path === '/android/wakAnd/v1/token/refresh') {
                $body=parseBody($raw,['observed_generation','reason']);
                if (!is_int($body['observed_generation']) || $body['observed_generation']<0 || !in_array($body['reason'],['expired','unauthorized'],true)) { throw new ApiError(400,'invalid_request'); }
                $result=$service->refresh($id,$secret,$body['observed_generation'],$body['reason']);
            } else {
                parseBody($raw,[]); $result=$service->disconnect($id,$secret);
                if ($result['revocation_pending'] || $result['provider_confirmation_required']) { http_response_code(202); }
            }
        }
        echo json_encode($result,JSON_THROW_ON_ERROR);
    } catch (\Throwable $e) {
        $known=$e instanceof ApiError;
        http_response_code($known ? $e->status : 503);
        if ($known && in_array($e->status,[409,429],true)) { header('Retry-After: 5'); }
        echo json_encode(['error'=>$known ? $e->getMessage() : 'service_unavailable','request_id'=>$requestId]);
    } finally {
        if ($service !== null) {
            try { $service->cleanup(microtime(true)-$started < 5); } catch (\Throwable $e) { /* Never expose cleanup failures or secrets. */ }
        }
    }
}
