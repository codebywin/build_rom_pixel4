const fs = require('fs');
const path = require('path');

const configPath = path.join(__dirname, '..', 'app', 'src', 'main', 'java', 'com', 'payout', 'app', 'AppConfig.kt');
const backupPath = path.join(__dirname, '..', 'app', 'src', 'main', 'java', 'com', 'payout', 'app', 'AppConfig.kt.clean_bak');

const action = process.argv[2] || 'obfuscate';
const MASK = 0x5A;

function encodeStringToBytes(str) {
    const buf = Buffer.from(str, 'utf8');
    const bytes = [];
    for (let i = 0; i < buf.length; i++) {
        bytes.push(buf[i] ^ MASK);
    }
    return `byteArrayOf(${bytes.join(', ')})`;
}

if (action === 'restore') {
    if (fs.existsSync(backupPath)) {
        fs.copyFileSync(backupPath, configPath);
        fs.unlinkSync(backupPath);
        console.log('[OBF] Đã khôi phục lại AppConfig.kt sạch nguyên bản!');
    } else {
        console.log('[OBF] Không có file backup để khôi phục.');
    }
} else if (action === 'obfuscate') {
    if (!fs.existsSync(configPath)) {
        console.error('[OBF ERROR] Không tìm thấy AppConfig.kt!');
        process.exit(1);
    }

    // 1. Sao lưu file sạch
    const originalContent = fs.readFileSync(configPath, 'utf8');
    fs.writeFileSync(backupPath, originalContent, 'utf8');

    // 2. Phân tích các hằng số chuỗi trong AppConfig.kt
    const stringRegex = /const\s+val\s+([A-Za-z0-9_]+)\s*=\s*"([^"]*)"/g;
    const intRegex = /const\s+val\s+([A-Za-z0-9_]+)\s*=\s*([0-9]+)/g;

    const stringConstants = {};
    const intConstants = {};

    let match;
    while ((match = stringRegex.exec(originalContent)) !== null) {
        stringConstants[match[1]] = match[2];
    }
    while ((match = intRegex.exec(originalContent)) !== null) {
        intConstants[match[1]] = match[2];
    }

    // 3. Tạo code obfuscated
    let obfCode = `package com.payout.app\n\n`;
    obfCode += `/**\n`;
    obfCode += ` * BẢN MÃ HÓA TỰ ĐỘNG KHI BUILD (DYNAMIC STRING OBFUSCATION)\n`;
    obfCode += ` * Toàn bộ IP, URL, Secret được giấu dưới dạng Byte động trong RAM.\n`;
    obfCode += ` */\n`;
    obfCode += `object AppConfig {\n`;
    obfCode += `    private const val MASK: Byte = 0x5A\n\n`;
    obfCode += `    private fun s(bytes: ByteArray): String {\n`;
    obfCode += `        val out = ByteArray(bytes.size)\n`;
    obfCode += `        for (i in bytes.indices) {\n`;
    obfCode += `            out[i] = (bytes[i].toInt() xor MASK.toInt()).toByte()\n`;
    obfCode += `        }\n`;
    obfCode += `        return String(out, Charsets.UTF_8)\n`;
    obfCode += `    }\n\n`;

    // Ghi các hằng số số nguyên (như port)
    for (const [key, val] of Object.entries(intConstants)) {
        obfCode += `    const val ${key} = ${val}\n`;
    }

    // Ghi các hằng số chuỗi đã mã hóa byte
    for (const [key, val] of Object.entries(stringConstants)) {
        obfCode += `    val ${key} get() = s(${encodeStringToBytes(val)})\n`;
    }

    obfCode += `}\n`;

    fs.writeFileSync(configPath, obfCode, 'utf8');
    console.log('[OBF] Đã mã hóa thành công AppConfig.kt sang dạng Byte obfuscated!');
}

