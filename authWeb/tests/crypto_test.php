<?php
declare(strict_types=1);
require __DIR__.'/../src/Core.php';
use WakAnd\Auth\ApiError;
use WakAnd\Auth\TokenCipher;
use function WakAnd\Auth\encodeId;
use function WakAnd\Auth\tokenCryptoStatus;
function verifyCrypto(bool $ok, string $label): void { if (!$ok) { throw new LogicException($label); } }
function rejectCrypto(callable $work): void {
    try { $work(); } catch (ApiError $e) { verifyCrypto($e->status===503 && $e->getMessage()==='service_unavailable','sanitized crypto failure'); return; }
    throw new LogicException('Expected authenticated decryption rejection');
}
$native=extension_loaded('sodium');
$status=tokenCryptoStatus();
verifyCrypto($status['available'] && $status['self_test'],'public known-answer vector');
verifyCrypto($status['backend']===($native ? 'native' : 'sodium_compat'),'actual runtime selection');
if ($native) { verifyCrypto(!class_exists('ParagonIE_Sodium_Compat',false),'native mode avoids loading polyfill'); }
require_once __DIR__.'/../src/_vendor/sodium_compat/autoload.php';
// Test-only: force the second implementation even when native sodium is installed.
ParagonIE_Sodium_Compat::$disableFallbackForUnitTests=true;
verifyCrypto(ParagonIE_Sodium_Compat::$fastMult===false,'safe multiplication default');
$key=str_repeat("\x42",32);
$config=['environment'=>'production','token_keys'=>['current'=>'v1','keys'=>['v1'=>base64_encode($key)]]];
$row=['link_id'=>str_repeat("\x11",16),'generation'=>7,'token_key_id'=>'v1','access_expires_at'=>2000000000,'granted_scope'=>'read_stats.projects,write_heartbeats'];
$bundle=['access_token'=>'synthetic-access','refresh_token'=>'synthetic-refresh','token_type'=>'Bearer','scope'=>$row['granted_scope'],'expires_at'=>$row['access_expires_at']];
$cipher=new TokenCipher($config); $blob=$cipher->encrypt($bundle,$row);
$aad='wakand|production|'.encodeId($row['link_id']).'|7|v1';
$pure=substr($blob,0,24).ParagonIE_Sodium_Compat::crypto_aead_xchacha20poly1305_ietf_encrypt(json_encode($bundle,JSON_THROW_ON_ERROR),$aad,substr($blob,0,24),$key);
verifyCrypto($blob===$pure,'same nonce/AAD/key produces identical bytes');
verifyCrypto(ParagonIE_Sodium_Compat::crypto_aead_xchacha20poly1305_ietf_decrypt(substr($blob,24),$aad,substr($blob,0,24),$key)===json_encode($bundle,JSON_THROW_ON_ERROR),'native record readable by polyfill');
$row['token_bundle']=$pure;
verifyCrypto($cipher->decrypt($row)===$bundle,'polyfill record readable by selected backend');
verifyCrypto($cipher->encrypt($bundle,$row)!==$blob,'new random nonce each encryption');
foreach ([0,24,strlen($blob)-1] as $offset) {
    $bad=$blob; $bad[$offset]=chr(ord($bad[$offset])^1);
    rejectCrypto(fn()=>$cipher->decrypt(array_replace($row,['token_bundle'=>$bad])));
}
foreach ([substr($blob,0,40),substr($blob,0,-1),''] as $bad) { rejectCrypto(fn()=>$cipher->decrypt(array_replace($row,['token_bundle'=>$bad]))); }
foreach (['generation'=>8,'link_id'=>str_repeat("\x22",16),'token_key_id'=>'unknown','access_expires_at'=>2000000001,'granted_scope'=>'write_heartbeats'] as $field=>$value) {
    rejectCrypto(fn()=>$cipher->decrypt(array_replace($row,[$field=>$value])));
}
rejectCrypto(fn()=>(new TokenCipher(array_replace($config,['environment'=>'test'])))->decrypt($row));
rejectCrypto(fn()=>(new TokenCipher(['environment'=>'production','token_keys'=>['keys'=>['v1'=>base64_encode('short')]]]))->encrypt($bundle,$row));
// Synthetic token-shaped payloads only. Report timings, never keys, tokens or ciphertext.
$bench=[];
foreach ([256,2048,16384] as $bytes) {
    $sample=$bundle; $sample['access_token']=str_repeat('x',intdiv($bytes,2)); $sample['refresh_token']=str_repeat('y',intdiv($bytes,2));
    $times=[];
    for ($i=0;$i<5;$i++) {
        $started=hrtime(true); $stored=$cipher->encrypt($sample,$row);
        verifyCrypto($cipher->decrypt(array_replace($row,['token_bundle'=>$stored]))===$sample,'benchmark authentication');
        $times[]=(hrtime(true)-$started)/1e6;
    }
    sort($times); $bench[]=['synthetic_token_bytes'=>$bytes,'roundtrip_median_ms'=>round($times[2],3),'roundtrip_max_ms'=>round($times[4],3)];
}
echo json_encode(['passed'=>true,'crypto'=>$status,'native_polyfill_byte_compatibility'=>true,'benchmarks'=>$bench],JSON_PRETTY_PRINT).PHP_EOL;
