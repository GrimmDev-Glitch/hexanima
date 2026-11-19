#!/usr/bin/env node
// List PNG chunks, text keys, and CRC/order validity for metadata debugging.
const fs = require('node:fs');

function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function inspect(path) {
  const raw = fs.readFileSync(path);
  if (raw.subarray(0, 8).toString('hex') !== '89504e470d0a1a0a') throw new Error('firma PNG incorrecta');
  let pos = 8, seenIdat = false, issues = 0;
  console.log(`Archivo: ${path} (${raw.length} bytes)`);
  while (pos + 12 <= raw.length) {
    const length = raw.readUInt32BE(pos), type = raw.toString('ascii', pos + 4, pos + 8);
    const end = pos + 12 + length;
    if (end > raw.length) { console.log(`ERROR: chunk truncado en offset ${pos}`); return 1; }
    const data = raw.subarray(pos + 8, pos + 8 + length);
    const expected = raw.readUInt32BE(pos + 8 + length);
    const actual = crc32(raw.subarray(pos + 4, pos + 8 + length));
    const key = ['tEXt', 'iTXt', 'zTXt'].includes(type) ? data.subarray(0, data.indexOf(0) < 0 ? data.length : data.indexOf(0)).toString('latin1') : '';
    const order = seenIdat && ['tEXt', 'iTXt', 'zTXt'].includes(type) ? 'después de IDAT' : '';
    const valid = expected === actual;
    console.log(`${type} key=${key || '-'} length=${length} CRC=${valid ? 'OK' : 'ERROR'}${order ? ` ${order}` : ''}`);
    if (!valid || order) issues++;
    if (type === 'IDAT') seenIdat = true;
    pos = end;
    if (type === 'IEND') break;
  }
  if (pos !== raw.length) { console.log(`AVISO: ${raw.length - pos} bytes sobrantes después de IEND`); issues++; }
  return issues ? 1 : 0;
}

if (process.argv.length !== 3) {
  console.error('Uso: node tools/png_chunks.js archivo.png');
  process.exit(2);
}
try { process.exit(inspect(process.argv[2])); }
catch (e) { console.error(e.message); process.exit(1); }
