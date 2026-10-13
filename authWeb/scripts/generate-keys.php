<?php
declare(strict_types=1);
// Run on the server, not through HTTP. Never print keys to a terminal or log.
if (PHP_SAPI !== 'cli') { http_response_code(404); exit; }
ini_set('display_errors','0'); ini_set('log_errors','0');
try {
    $path='/home/yui-kitamura/.config/wakand/generated-keys.php';
    umask(0077);
    $file=fopen($path,'x'); // Exclusive creation; never overwrite existing key material.
    if ($file === false) { throw new RuntimeException(); }
    try {
        $content='<?php return ' . var_export(['token_keys'=>['current'=>'v1','keys'=>['v1'=>base64_encode(random_bytes(32))]],
            'rate_limit_key'=>base64_encode(random_bytes(32))],true) . ';' . PHP_EOL;
        if (fwrite($file,$content) !== strlen($content)) { throw new RuntimeException(); }
        fflush($file);
    } finally { fclose($file); }
    echo "Private key file created. Merge its fields into the existing private wakand section; do not share values.\n";
} catch (Throwable $e) { fwrite(STDERR,"Key generation unavailable; check private directory and existing key file.\n"); exit(1); }
