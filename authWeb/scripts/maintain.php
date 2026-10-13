<?php
declare(strict_types=1);
if (PHP_SAPI !== 'cli') { http_response_code(404); exit; }
ini_set('display_errors','0'); ini_set('log_errors','0');
try {
    foreach (['Config','Core','Database','OAuthService'] as $name) { require __DIR__ . '/../src/' . $name . '.php'; }
    $config=\WakAnd\Auth\loadConfig('/home/yui-kitamura/.config/wakand/oauth.php','/home/yui-kitamura/www');
    $config['maintenance']=false; // Administrator CLI may drain operations while HTTP is closed.
    $config=\WakAnd\Auth\runtimeConfig($config);
    $ddl=str_replace("\r\n","\n",file_get_contents(__DIR__ . '/../schema/001_oauth_mysql.sql'));
    $repo=new \WakAnd\Auth\Repository(\WakAnd\Auth\connectDatabase($config),[hash('sha256',$ddl),hash('sha256',str_replace("\n","\r\n",$ddl))]);
    $service=new \WakAnd\Auth\OAuthService($repo,new \WakAnd\Auth\WakaTimeProvider($config),$config);
    $service->cleanup();
    $pending=$repo->query("SELECT COUNT(*) AS pending_count, MIN(updated_at) AS oldest_pending FROM oauth_links WHERE status IN ('revoke_pending','revoking') OR revocation_uncertain=1")->fetch();
    echo json_encode($pending) . PHP_EOL;
} catch (Throwable $e) { fwrite(STDERR,"Maintenance unavailable. Use private runtime checks.\n"); exit(1); }
