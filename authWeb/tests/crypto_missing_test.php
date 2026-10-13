<?php
declare(strict_types=1);
if (extension_loaded('sodium')) { throw new LogicException('Run this missing-dependency test with php -n'); }
$root=sys_get_temp_dir().'/wakand-crypto-missing-'.bin2hex(random_bytes(8)); mkdir($root,0700);
try {
    copy(__DIR__.'/../src/Core.php',$root.'/Core.php'); copy(__DIR__.'/../src/_crypto.php',$root.'/_crypto.php');
    $partial=in_array('--partial',$argv,true);
    if ($partial) {
        mkdir($root.'/_vendor/sodium_compat',0700,true);
        copy(__DIR__.'/../src/_vendor/sodium_compat/autoload.php',$root.'/_vendor/sodium_compat/autoload.php');
    }
    require $root.'/Core.php';
    ob_start(); $status=\WakAnd\Auth\tokenCryptoStatus(); $output=ob_get_clean();
    if ($output!=='' || $status['available'] || $status['self_test']) { throw new LogicException('Missing dependency must fail closed without output'); }
    try { \WakAnd\Auth\requireTokenCrypto(); throw new LogicException('Expected failure'); }
    catch (\WakAnd\Auth\ApiError $e) { if ($e->status!==503 || $e->getMessage()!=='service_unavailable') { throw new LogicException('Unsanitized failure'); } }
    echo "Missing crypto dependency: sanitized 503, no output.\n";
} finally {
    if (is_file($root.'/_vendor/sodium_compat/autoload.php')) {
        unlink($root.'/_vendor/sodium_compat/autoload.php'); rmdir($root.'/_vendor/sodium_compat'); rmdir($root.'/_vendor');
    }
    unlink($root.'/Core.php'); unlink($root.'/_crypto.php'); rmdir($root);
}
