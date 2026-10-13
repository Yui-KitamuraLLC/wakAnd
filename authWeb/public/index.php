<?php
declare(strict_types=1);

// Deployment probe only. OAuth endpoints are not implemented yet.
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
