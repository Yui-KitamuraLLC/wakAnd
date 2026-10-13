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
    return array_intersect_key($config, array_flip(['client_id', 'client_secret', 'redirect_uri']));
}
