<?php
declare(strict_types=1);

// Root remains a deployment probe; OAuth readiness is checked by the private CLI.
$path = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH);
if (!in_array($path, ['/android/wakAnd/', '/android/wakAnd/index.php'], true)) {
    ini_set('display_errors', '0');
    ini_set('log_errors', '0');
    try {
        foreach (['_config.php', '_core.php', '_database.php', '_oauth.php', '_http.php'] as $library) {
            require __DIR__ . '/' . $library;
        }
        \WakAnd\Auth\handleHttp();
    } catch (\Throwable $e) {
        http_response_code(503);
        header('Content-Type: application/json; charset=utf-8');
        header('Cache-Control: no-store');
        echo json_encode(['error'=>'service_unavailable','request_id'=>bin2hex(random_bytes(8))]);
    }
    exit;
}
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

if (!in_array($_SERVER['REQUEST_METHOD'] ?? '', ['GET', 'HEAD'], true)) {
    http_response_code(405);
    header('Allow: GET, HEAD');
    echo json_encode(['error' => 'method_not_allowed']);
    exit;
}

$path = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH);
if (!in_array($path, ['/android/wakAnd/', '/android/wakAnd/index.php'], true)) {
    http_response_code(404);
    echo json_encode(['error' => 'not_found']);
    exit;
}

echo json_encode(['service' => 'wakand-auth', 'oauth_ready' => false]);
