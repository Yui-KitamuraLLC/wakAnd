<?php
declare(strict_types=1);
namespace WakAnd\Auth;

function connectDatabase(array $config): \PDO
{
    $db = $config['database'];
    $options = [\PDO::ATTR_ERRMODE => \PDO::ERRMODE_EXCEPTION, \PDO::ATTR_EMULATE_PREPARES => false,
        \PDO::ATTR_PERSISTENT => false, \PDO::ATTR_DEFAULT_FETCH_MODE => \PDO::FETCH_ASSOC,
        \PDO::ATTR_TIMEOUT => 5];
    if ($db['tls_mode'] === 'required') {
        $options[\PDO::MYSQL_ATTR_SSL_CA] = $db['tls_ca'];
        $options[\PDO::MYSQL_ATTR_SSL_VERIFY_SERVER_CERT] = true;
    }
    $pdo = new \PDO('mysql:host=' . $db['host'] . ';port=' . $db['port'] . ';dbname=' . $db['name'] . ';charset=utf8mb4', $db['user'], $db['password'], $options);
    $pdo->exec("SET SESSION time_zone = '+00:00'");
    $pdo->exec('SET SESSION innodb_lock_wait_timeout = 5');
    if (!str_contains((string)$pdo->query('SELECT @@SESSION.sql_mode')->fetchColumn(), 'STRICT_')) {
        throw new ApiError(503, 'service_unavailable');
    }
    $cipher = $pdo->query("SHOW SESSION STATUS LIKE 'Ssl_cipher'")->fetch();
    if ($db['tls_mode'] === 'required' && ($cipher['Value'] ?? '') === '') { throw new ApiError(503, 'service_unavailable'); }
    return $pdo;
}

final class Repository
{
    private const COLUMNS = ['device_secret_hash','status','disconnect_requested','revocation_uncertain','generation',
        'token_key_id','token_bundle','access_expires_at','granted_scope','operation_id','unresolved_operation_id',
        'operation_deadline','revoke_attempts','next_revoke_at','last_error_code','created_at','updated_at','disconnected_at','revoked_at'];
    private const SESSION_COLUMNS = ['state_hash','launch_token_hash','verifier_challenge','browser_nonce_hash','outcome',
        'created_at','expires_at','state_consumed_at','completed_at'];
    public function __construct(public readonly \PDO $db, array $checksums)
    {
        if (!str_starts_with((string)$db->query('SELECT VERSION()')->fetchColumn(), '8.0.')) { throw new ApiError(503, 'service_unavailable'); }
        $engines=$this->query("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('oauth_links','oauth_link_sessions','oauth_rate_buckets','oauth_schema_versions') AND ENGINE='InnoDB'")->fetchColumn();
        if ((int)$engines !== 4) { throw new ApiError(503, 'service_unavailable'); }
        $foreignKeys=$this->query("SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='oauth_link_sessions' AND REFERENCED_TABLE_NAME='oauth_links' AND CONSTRAINT_NAME='fk_sessions_link'")->fetchColumn();
        if ((int)$foreignKeys !== 1) { throw new ApiError(503, 'service_unavailable'); }
        $rows = $this->query('SELECT version, LOWER(HEX(checksum_sha256)) AS checksum FROM oauth_schema_versions')->fetchAll();
        if (count($rows) !== 1 || (int)$rows[0]['version'] !== 1 || !in_array($rows[0]['checksum'], $checksums, true)) {
            throw new ApiError(503, 'service_unavailable');
        }
    }
    public function query(string $sql, array $values = []): \PDOStatement
    { $stmt = $this->db->prepare($sql); $stmt->execute($values); return $stmt; }
    public function now(): int { return (int)$this->db->query('SELECT UNIX_TIMESTAMP()')->fetchColumn(); }
    public function transaction(callable $work): mixed
    {
        $this->db->beginTransaction();
        try { $result = $work(); $this->db->commit(); }
        catch (\Throwable $e) { if ($this->db->inTransaction()) { $this->db->rollBack(); } throw $e; }
        // Returning an error lets expiry/recovery transitions commit before reporting it.
        if ($result instanceof ApiError) { throw $result; }
        return $result;
    }
    public function link(string $id): array
    {
        $row = $this->query('SELECT * FROM oauth_links WHERE link_id = ? FOR UPDATE', [$id])->fetch();
        if (!$row) { throw new ApiError(401, 'device_unauthorized'); }
        return $row;
    }
    public function session(string $id): array
    {
        $row = $this->query('SELECT * FROM oauth_link_sessions WHERE link_id = ? FOR UPDATE', [$id])->fetch();
        if (!$row) { throw new ApiError(410, 'link_expired'); }
        return $row;
    }
    public function update(string $id, array $values, bool $session = false): void
    {
        $allow = $session ? self::SESSION_COLUMNS : self::COLUMNS;
        if (!$values || array_diff(array_keys($values), $allow)) { throw new \LogicException('Invalid repository columns'); }
        $sql = implode(', ', array_map(static fn($key) => $key . ' = ?', array_keys($values)));
        $this->query('UPDATE ' . ($session ? 'oauth_link_sessions' : 'oauth_links') . ' SET ' . $sql . ' WHERE link_id = ?', [...array_values($values), $id]);
    }
    public function rate(string $label, string $subject, int $window, int $limit, string $key): void
    {
        $now = $this->now(); $start = intdiv($now, $window) * $window;
        $hash = hash_hmac('sha256', $label . '|' . $subject, base64_decode($key, true), true);
        $this->query('INSERT INTO oauth_rate_buckets (bucket_key,window_start,hits,expires_at) VALUES (?,?,1,?) ON DUPLICATE KEY UPDATE hits=hits+1', [$hash,$start,$start+$window+3600]);
        $hits = $this->query('SELECT hits FROM oauth_rate_buckets WHERE bucket_key=? AND window_start=?', [$hash,$start])->fetchColumn();
        if ((int)$hits > $limit) { throw new ApiError(429, 'rate_limited'); }
    }
}
