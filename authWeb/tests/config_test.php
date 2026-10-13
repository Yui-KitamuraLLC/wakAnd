<?php
declare(strict_types=1);

require __DIR__ . '/../src/Config.php';

$root = sys_get_temp_dir() . '/wakand-config-' . bin2hex(random_bytes(8));
mkdir($root);
mkdir($root . '/www');
$valid = ['wakand' => [
    'client_id' => 'test-client',
    'client_secret' => 'fake-secret-for-test',
    'redirect_uri' => 'https://yui.eng.pro/android/wakAnd/oauth/callback',
]];
function writeConfig(string $path, array $values): void
{
    file_put_contents($path, '<?php return ' . var_export($values, true) . ';');
}
function rejectConfig(string $path, string $webRoot): void
{
    ob_start();
    try {
        \WakAnd\Auth\loadConfig($path, $webRoot);
        throw new \LogicException('Expected rejection');
    } catch (\RuntimeException $error) {
        if ($error->getMessage() !== 'Private OAuth configuration is unavailable or invalid.'
            || $error->getPrevious() !== null || ob_get_contents() !== '') {
            throw new \LogicException('Configuration details leaked');
        }
    } finally {
        ob_end_clean();
    }
}

try {
    $path = $root . '/.php.config';
    writeConfig($path, $valid);
    if (\WakAnd\Auth\loadConfig($path, $root . '/www') !== $valid['wakand']) {
        throw new \LogicException('Valid configuration rejected');
    }
    rejectConfig($root . '/missing', $root . '/www');
    writeConfig($root . '/www/exposed.php', $valid);
    rejectConfig($root . '/www/exposed.php', $root . '/www');
    symlink($root . '/www/exposed.php', $root . '/link.php');
    rejectConfig($root . '/link.php', $root . '/www');
    $invalid = $valid;
    $invalid['wakand']['client_secret'] = 'REPLACE_WITH_WAKATIME_APP_SECRET';
    writeConfig($path, $invalid);
    rejectConfig($path, $root . '/www');
    $invalid = $valid;
    $invalid['wakand']['redirect_uri'] = 'https://example.com/callback';
    writeConfig($path, $invalid);
    rejectConfig($path, $root . '/www');
    file_put_contents($path, '<?php echo "fake-secret-for-test"; return [];');
    rejectConfig($path, $root . '/www');
    file_put_contents($path, '<?php throw new Exception("fake-secret-for-test");');
    rejectConfig($path, $root . '/www');
    file_put_contents($path, '<?php invalid php here');
    rejectConfig($path, $root . '/www');
    $runtime = $valid['wakand'] + ['environment' => 'production', 'maintenance' => false,
        'database' => ['host'=>'mysql50009.db.sakura.ne.jp','port'=>3306,'name'=>'db50125_wakand_wakand',
            'user'=>'db50125_wakand_wakand','password'=>'fake-db-password','tls_mode'=>'provider_internal'],
        'token_keys'=>['current'=>'v1','keys'=>['v1'=>base64_encode(random_bytes(32))]],
        'rate_limit_key'=>base64_encode(random_bytes(32))];
    \WakAnd\Auth\runtimeConfig($runtime);
    foreach (['maintenance'=>true, 'environment'=>'development', 'rate_limit_key'=>$runtime['token_keys']['keys']['v1'],
        'database'=>array_replace($runtime['database'], ['tls_mode'=>'automatic'])] as $field=>$value) {
        try { \WakAnd\Auth\runtimeConfig(array_replace($runtime,[$field=>$value])); throw new \LogicException('Expected runtime rejection'); }
        catch (\RuntimeException $error) { if ($error->getMessage() !== 'Private OAuth configuration is unavailable or invalid.') { throw new \LogicException('Config details leaked'); } }
    }
    echo "Private config: boundary, redaction, maintenance, keys and explicit DB transport checks passed\n";
} finally {
    foreach ([$root . '/link.php', $root . '/www/exposed.php', $root . '/.php.config'] as $file) {
        if (is_link($file) || is_file($file)) {
            unlink($file);
        }
    }
    rmdir($root . '/www');
    rmdir($root);
}
