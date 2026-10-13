<?php
declare(strict_types=1);
namespace WakAnd\Auth;

/** One known-answer check per request, with public test data only. No remote loading. */
function tokenCryptoStatus(): array
{
    static $status=null;
    if ($status !== null) { return $status; }
    $status=['backend'=>null,'available'=>false,'self_test'=>false,'self_test_ms'=>null,'php_64bit'=>PHP_INT_SIZE===8];
    $started=hrtime(true);
    try {
        if (extension_loaded('sodium')) {
            $status['backend']='native';
        } else {
            // The bundled v2 runtime requires PHP >=8.1 with 64-bit integers.
            if (PHP_VERSION_ID<80100 || PHP_INT_SIZE!==8) { return $status; }
            $autoload=__DIR__ . '/_vendor/sodium_compat/autoload.php';
            if (!is_readable($autoload)) { return $status; }
            require_once $autoload;
            // Keep upstream's safe multiplication default. No speed/security tradeoff switch.
            if (\ParagonIE_Sodium_Compat::$fastMult !== false) { return $status; }
            $status['backend']='sodium_compat';
        }
        if (!function_exists('sodium_crypto_aead_xchacha20poly1305_ietf_encrypt')
            || !function_exists('sodium_crypto_aead_xchacha20poly1305_ietf_decrypt')
            || !defined('SODIUM_CRYPTO_AEAD_XCHACHA20POLY1305_IETF_NPUBBYTES')
            || SODIUM_CRYPTO_AEAD_XCHACHA20POLY1305_IETF_NPUBBYTES!==24) { return $status; }
        // libsodium's public XChaCha20-Poly1305 known-answer vector (upstream KAT).
        $key=hex2bin('808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f');
        $nonce=hex2bin('404142434445464748494a4b4c4d4e4f5051525354555657');
        $aad=hex2bin('50515253c0c1c2c3c4c5c6c7');
        $plain=hex2bin('4c616469657320616e642047656e746c656d656e206f662074686520636c617373206f66202739393a204966204920636f756c64206f6666657220796f75206f6e6c79206f6e652074697020666f7220746865206675747572652c2073756e73637265656e20776f756c642062652069742e');
        $expected=hex2bin('bd6d179d3e83d43b9576579493c0e939572a1700252bfaccbed2902c21396cbb731c7f1b0b4aa6440bf3a82f4eda7e39ae64c6708c54c216cb96b72e1213b4522f8c9ba40db5d945b11b69b982c1bb9e3f3fac2bc369488f76b2383565d3fff921f9664c97637da9768812f615c68b13b52ec0875924c1c7987947deafd8780acf49');
        $actual=sodium_crypto_aead_xchacha20poly1305_ietf_encrypt($plain,$aad,$nonce,$key);
        $status['self_test']=hash_equals($expected,$actual)
            && sodium_crypto_aead_xchacha20poly1305_ietf_decrypt($expected,$aad,$nonce,$key)===$plain;
        $status['available']=$status['self_test'];
    } catch (\Throwable $e) { /* Return no exception, paths, input data or keys. */ }
    finally { $status['self_test_ms']=round((hrtime(true)-$started)/1e6,3); }
    return $status;
}

function requireTokenCrypto(): void
{
    if (!tokenCryptoStatus()['available']) { throw new ApiError(503,'service_unavailable'); }
}
