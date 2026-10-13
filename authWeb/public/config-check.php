<?php
declare(strict_types=1);

// Report only readiness, never values, paths, or exception details.
ini_set('display_errors', '0');
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');
if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'GET') {
    http_response_code(405);
    header('Allow: GET');
    echo '{"error":"method_not_allowed"}';
    exit;
}

try {
    require __DIR__ . '/_config.php';
    $config = \WakAnd\Auth\loadConfig(
        '/home/yui-kitamura/.config/wakand/oauth.php',
        '/home/yui-kitamura/www'
    );
    $configured = $config['client_id'] === 'pJDPWyzHvrc7iconHrnmrPaa';
    unset($config);
} catch (\Throwable $error) {
    $configured = false;
}
http_response_code($configured ? 200 : 503);
echo json_encode(['configured' => $configured]);
