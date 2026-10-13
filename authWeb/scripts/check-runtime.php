<?php
declare(strict_types=1);
if (PHP_SAPI !== 'cli') { http_response_code(404); exit; }
ini_set('display_errors','0'); ini_set('log_errors','0');
require __DIR__ . '/../src/Config.php';
require __DIR__ . '/../src/Core.php';
require __DIR__ . '/../src/Database.php';
$result=['extensions'=>false,'configuration'=>false,'database'=>false,'schema'=>false,'tls_encrypted'=>false,'tls_mode'=>null];
try {
    $result['extensions']=extension_loaded('pdo_mysql') && extension_loaded('curl') && extension_loaded('sodium');
    if (!$result['extensions']) { throw new RuntimeException(); }
    $config=\WakAnd\Auth\loadConfig('/home/yui-kitamura/.config/wakand/oauth.php','/home/yui-kitamura/www');
    $config['maintenance']=false; // Local diagnostics only; do not rewrite the private file.
    $config=\WakAnd\Auth\runtimeConfig($config); $result['configuration']=true;
    $result['tls_mode']=$config['database']['tls_mode'];
    $db=\WakAnd\Auth\connectDatabase($config); $result['database']=true;
    $ssl=$db->query("SHOW SESSION STATUS LIKE 'Ssl_cipher'")->fetch(); $result['tls_encrypted']=($ssl['Value'] ?? '') !== '';
    $ddl=str_replace("\r\n","\n",file_get_contents(__DIR__ . '/../schema/001_oauth_mysql.sql'));
    new \WakAnd\Auth\Repository($db,[hash('sha256',$ddl),hash('sha256',str_replace("\n","\r\n",$ddl))]);
    $result['schema']=true;
} catch (Throwable $e) { /* Return flags only. No driver messages, settings, SQL parameters or paths. */ }
unset($config,$db);
echo json_encode($result,JSON_UNESCAPED_SLASHES) . PHP_EOL;
exit($result['schema'] ? 0 : 1);
