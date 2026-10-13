<?php
declare(strict_types=1);
// Disabled unless the administrator places a private, short-lived access file via FTP.
ini_set('display_errors','0'); ini_set('log_errors','0');
try {
    foreach (['_config.php','_core.php','_database.php','_oauth.php','_http.php','_admin.php'] as $library) { require __DIR__ . '/' . $library; }
    \WakAnd\Auth\handleAdmin();
} catch (\Throwable $e) {
    http_response_code(503); header('Content-Type: application/json; charset=utf-8'); header('Cache-Control: no-store');
    echo '{"error":"service_unavailable"}';
}
