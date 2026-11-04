// Runs the packaged JAR twice against a disposable database, never against giavien.
const { spawn, spawnSync } = require('node:child_process');
const { randomBytes } = require('node:crypto');
const { createWriteStream } = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const container = process.env.PG_CONTAINER || 'giavien-postgres-1';
const database = 'giavien_test_' + randomBytes(16).toString('hex');
const port = process.env.SMOKE_PORT || '18080';
if (!/^\d+$/.test(port)) throw new Error('SMOKE_PORT must be numeric');
if (!process.env.TEST_POSTGRES_PASSWORD) throw new Error('TEST_POSTGRES_PASSWORD is required');
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
const adminPassword = randomBytes(24).toString('hex');
let app;
let created = false;

function docker(...args) {
  const result = spawnSync('docker', ['exec', container, ...args], { encoding: 'utf8', timeout: 30000 });
  if (result.status !== 0) throw new Error(result.stderr || result.error?.message || 'Docker command failed');
  return result.stdout.trim();
}
function sql(statement) {
  return docker('psql', '-U', 'giavien', '-d', database, '-At', '-v', 'ON_ERROR_STOP=1', '-c', statement);
}
async function stop() {
  if (!app || app.exitCode !== null) return;
  const exited = new Promise(resolve => app.once('exit', resolve));
  app.kill();
  const timer = setTimeout(() => app.kill('SIGKILL'), 10000);
  await exited;
  clearTimeout(timer);
}
async function start(withSecret, label) {
  const log = createWriteStream(path.join('target', `smoke-${label}.log`));
  app = spawn(java, ['-jar', 'target/giavien-restaurant-0.1.0.jar'], {
    env: { ...process.env,
      SPRING_PROFILES_ACTIVE: 'production', SPRING_CONFIG_IMPORT: '',
      DATABASE_URL: `jdbc:postgresql://127.0.0.1:15432/${database}`,
      DATABASE_USER: 'giavien', DATABASE_PASSWORD: process.env.TEST_POSTGRES_PASSWORD,
      BOOTSTRAP_ADMIN_EMAIL: withSecret ? 'owner@example.test' : '',
      BOOTSTRAP_ADMIN_PASSWORD: withSecret ? adminPassword : '',
      DEMO_PAYMENT_ENABLED: 'false', APP_DEMO: 'false',
      VNPAY_TMN_CODE: '', VNPAY_HASH_SECRET: '', SERVER_ADDRESS: '127.0.0.1', PORT: port,
    }, windowsHide: true,
  });
  let error;
  app.on('error', value => { error = value; });
  app.stdout.pipe(log, { end: false });
  app.stderr.pipe(log, { end: false });
  app.once('close', () => log.end());
  for (let attempt = 0; attempt < 90; attempt++) {
    if (error) throw error;
    if (app.exitCode !== null) throw new Error(`JAR exited; see target/smoke-${label}.log`);
    try {
      const response = await fetch(`http://127.0.0.1:${port}/login`, { signal: AbortSignal.timeout(1500) });
      if (response.ok && sql("SELECT COUNT(*) FROM app_user WHERE role='ADMIN'") === '1') {
        for (const route of ['/', '/menu', '/login']) {
          assert.equal((await fetch(`http://127.0.0.1:${port}${route}`)).status, 200);
        }
        return;
      }
    } catch { /* startup may still be creating schema and seeding */ }
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  throw new Error(`JAR did not become ready; see target/smoke-${label}.log`);
}
(async () => {
  try {
    // A live listener could make a failed new process look healthy.
    const probe = require('node:net').createServer();
    await new Promise((resolve, reject) => { probe.once('error', reject); probe.listen(Number(port), '127.0.0.1', resolve); });
    await new Promise(resolve => probe.close(resolve));
    docker('createdb', '-U', 'giavien', database);
    created = true;
    await start(true, 'first');
    assert.equal(sql('SELECT COUNT(*) FROM app_user'), '1');
    const fingerprint = sql("SELECT id||':'||email||':'||password_hash||':'||role FROM app_user");
    sql('UPDATE restaurant_settings SET deposit_per_table=123456 WHERE id=1');
    await stop();
    await start(false, 'restart');
    assert.equal(sql("SELECT id||':'||email||':'||password_hash||':'||role FROM app_user"), fingerprint);
    assert.equal(sql('SELECT deposit_per_table FROM restaurant_settings WHERE id=1'), '123456');
    console.log('PASS: production bootstrap, HTTP routes, restart without secrets and persisted data');
  } finally {
    await stop();
    if (created) docker('dropdb', '-U', 'giavien', database);
  }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
