<?php
declare(strict_types=1);

// Copy to ~/.config/wakand/oauth.php OUTSIDE ~/www/, then edit that copy only.
return [
    'wakand' => [
        'client_id' => 'pJDPWyzHvrc7iconHrnmrPaa',
        'client_secret' => 'REPLACE_WITH_WAKATIME_APP_SECRET',
        'redirect_uri' => 'https://yui.eng.pro/android/wakAnd/oauth/callback',
        'environment' => 'production',
        'maintenance' => true, // Keep true until deployment, schema and connection checks pass.
        'database' => [
            'host' => 'mysql50009.db.sakura.ne.jp',
            'port' => 3306,
            'name' => 'db50125_wakand_wakand',
            'user' => 'db50125_wakand_wakand',
            'password' => 'REPLACE_WITH_DATABASE_PASSWORD',
            'tls_mode' => 'REPLACE_WITH_REQUIRED_OR_PROVIDER_INTERNAL',
            'tls_ca' => '', // For required: private path to a trusted CA certificate.
        ],
        'token_keys' => ['current' => 'v1', 'keys' => ['v1' => 'REPLACE_WITH_32_BYTE_BASE64_KEY']],
        'rate_limit_key' => 'REPLACE_WITH_SEPARATE_32_BYTE_BASE64_KEY',
    ],
];
