<?php
declare(strict_types=1);

namespace WakAnd\Auth;

/** Arguments must be trusted server paths, never HTTP input. */
function loadConfig(string $configPath, string $webRoot): array
{
    $failure = 'Private OAuth configuration is unavailable or invalid.';
    $configFile = realpath($configPath);
    $publicDirectory = realpath($webRoot);
    if ($configFile === false || $publicDirectory === false || !is_dir($publicDirectory)
        || !is_file($configFile) || !is_readable($configFile)) {
        throw new \RuntimeException($failure);
    }
    // Resolve symlinks before checking the boundary. Use the entire ~/www/ tree.
    $publicPrefix = rtrim($publicDirectory, DIRECTORY_SEPARATOR) . DIRECTORY_SEPARATOR;
    if (str_starts_with($configFile, $publicPrefix)) {
        throw new \RuntimeException($failure);
    }

    // Never emit accidental output, PHP warnings, or exception contents from a secret file.
    ob_start();
    try {
        $settings = @include $configFile;
        $unexpectedOutput = ob_get_contents();
    } catch (\Throwable $error) {
        throw new \RuntimeException($failure);
    } finally {
        ob_end_clean();
    }
    $config = is_array($settings) ? ($settings['wakand'] ?? null) : null;
    if ($unexpectedOutput !== '' || !is_array($config)) {
        throw new \RuntimeException($failure);
    }
    foreach (['client_id', 'client_secret', 'redirect_uri'] as $key) {
        if (!isset($config[$key]) || !is_string($config[$key]) || trim($config[$key]) === ''
            || str_starts_with($config[$key], 'REPLACE_')) {
            throw new \RuntimeException($failure);
        }
    }
    if ($config['redirect_uri'] !== 'https://yui.eng.pro/android/wakAnd/oauth/callback') {
        throw new \RuntimeException($failure);
    }
    return $config;
}

function runtimeConfig(array $config): array
{
    $failure = 'Private OAuth configuration is unavailable or invalid.';
    if (($config['maintenance'] ?? true) !== false || ($config['environment'] ?? '') !== 'production') {
        throw new \RuntimeException($failure);
    }
    $db = $config['database'] ?? [];
    foreach (['host', 'name', 'user', 'password', 'tls_mode'] as $field) {
        if (!isset($db[$field]) || !is_string($db[$field]) || $db[$field] === ''
            || str_starts_with($db[$field], 'REPLACE_') || str_contains($db[$field], "\0")) {
            throw new \RuntimeException($failure);
        }
    }
    if ($db['host'] !== 'mysql50009.db.sakura.ne.jp' || $db['name'] !== 'db50125_wakand_wakand'
        || $db['user'] !== 'db50125_wakand_wakand' || ($db['port'] ?? null) !== 3306
        || !in_array($db['tls_mode'], ['required', 'provider_internal'], true)) { throw new \RuntimeException($failure); }
    if ($db['tls_mode'] === 'required' && (!isset($db['tls_ca']) || !is_string($db['tls_ca']) || !is_readable($db['tls_ca']))) {
        throw new \RuntimeException($failure);
    }
    $keys = $config['token_keys'] ?? [];
    if (!isset($keys['current'], $keys['keys']) || !is_string($keys['current']) || !is_array($keys['keys'])
        || !array_key_exists($keys['current'], $keys['keys'])) { throw new \RuntimeException($failure); }
    foreach ($keys['keys'] as $id => $value) {
        if (!is_string($id) || !preg_match('/^[A-Za-z0-9_-]{1,32}$/D', $id) || !is_string($value)
            || strlen(base64_decode($value, true) ?: '') !== 32) { throw new \RuntimeException($failure); }
    }
    if (!isset($config['rate_limit_key']) || !is_string($config['rate_limit_key'])
        || strlen(base64_decode($config['rate_limit_key'], true) ?: '') !== 32
        || in_array($config['rate_limit_key'], $keys['keys'], true)) { throw new \RuntimeException($failure); }
    return $config;
}
