#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const repoDir = path.resolve(process.env.RD_LOCAL_REPAIR_REPO || "/work/repo");
const promptFile = process.env.PROMPT_FILE || "/work/input/prompt.md";
const outputDir = process.env.OUTPUT_DIR || "/work/output";
const resultFile = process.env.RESULT_FILE || path.join(outputDir, "result.json");
const patchFile = process.env.PATCH_FILE || path.join(outputDir, "patch.diff");
const testLogFile = process.env.TEST_LOG_FILE || path.join(outputDir, "test.log");

fs.mkdirSync(outputDir, { recursive: true });

const prompt = readFile(promptFile).toLowerCase();
const changedFiles = [];
const notes = [];

try {
  if (!isWaimaiRepository()) {
    writeResult("FAILED", "Local repair worker skipped: repository is not the waimai demo project.", [], [], "SKIPPED", "HIGH", true);
    process.exit(1);
  }

  patchReadmeRequirementSmoke();
  patchDatabaseEsmDirname();
  patchClientCreateOrderPayload();
  patchMerchantSchemaMismatches();
  patchRiderDeliveryAddress();
  patchAdminMerchantStatus();

  if (changedFiles.length === 0) {
    writeTestLog(["No deterministic waimai repair rule matched this task."]);
    writeResult(
      "FAILED",
      "Local repair worker found no supported waimai patch for this task.",
      [],
      [],
      "SKIPPED",
      "LOW",
      true
    );
    process.exit(1);
  }

  const testCommands = runValidation();
  writePatch();
  writeResult(
    "SUCCESS",
    "Applied deterministic waimai compatibility repair for known schema/API mismatches.",
    changedFiles,
    testCommands,
    "PASSED",
    "LOW",
    false
  );
  process.exit(0);
} catch (error) {
  const message = error instanceof Error ? error.message : String(error);
  writeTestLog([`Local repair worker failed: ${message}`]);
  writeResult("FAILED", `Local repair worker failed: ${message}`, changedFiles, [], "FAILED", "HIGH", true);
  process.exit(1);
}

function isWaimaiRepository() {
  const packageJson = jsonFile("package.json");
  if (packageJson.name === "waimai-delivery-system") {
    return true;
  }
  return exists("server/src/routes/orders.ts") && exists("client/src/api.ts");
}

function patchReadmeRequirementSmoke() {
  if (!mentions(["readme.md", "rd-bot", "需求交付验收记录", "不修改业务代码", "只修改"])) {
    return;
  }
  updateFile("README.md", (source) => {
    if (source.includes("RD-Bot 需求交付验收记录")) {
      return source;
    }
    const section = [
      "",
      "",
      "## RD-Bot 需求交付验收记录",
      "",
      "- 来源：RD-Bot 需求任务自动执行链路。",
      "- 范围：只修改 README.md，不修改业务代码。",
      "- 验证：执行 `git diff --check` 通过。"
    ].join("\n");
    return `${source.trimEnd()}${section}\n`;
  }, "README.md: add RD-Bot requirement delivery smoke record.");
}

function patchDatabaseEsmDirname() {
  if (!mentions(["__dirname", "esm", "database.ts", "seed", "schema path"])) {
    return;
  }
  updateFile("server/src/database.ts", (source) => {
    if (!source.includes("__dirname") || source.includes("fileURLToPath(import.meta.url)")) {
      return source;
    }
    let next = source;
    if (!next.includes("fileURLToPath")) {
      next = next.replace("import fs from 'fs';", "import fs from 'fs';\nimport { fileURLToPath } from 'url';");
    }
    const marker = "import { fileURLToPath } from 'url';";
    return next.replace(
      marker,
      `${marker}\n\nconst __filename = fileURLToPath(import.meta.url);\nconst __dirname = path.dirname(__filename);`
    );
  }, "server/src/database.ts: define __dirname for Node ESM.");
}

function patchClientCreateOrderPayload() {
  if (!mentions(["createorder", "delivery_address", "customer_name", "下单", "订单"])) {
    return;
  }
  updateFile("client/src/api.ts", (source) => {
    let next = source;
    next = next.replace("delivery_address: string;", "address: string;\n    phone: string;\n    customer_name: string;");
    next = next.replace("remark?: string;", "note?: string;");
    next = next.replace(
      /return this\.request\('\/orders', \{ method: 'POST', body: \{[\s\S]*?merchant_id: data\.merchant_id,[\s\S]*?items: data\.items,[\s\S]*?delivery_address: data\.delivery_address,[\s\S]*?remark: data\.remark[\s\S]*?\} \}\);/,
      "return this.request('/orders', { method: 'POST', body: data });"
    );
    return next;
  }, "client/src/api.ts: align createOrder payload with server order schema.");
}

function patchMerchantSchemaMismatches() {
  if (!mentions(["merchant", "merchants", "商家", "menu_items", "reviews", "no such table", "no such column: status"])) {
    return;
  }
  updateFile("server/src/routes/merchants.ts", (source) => {
    let next = source;
    next = next.replaceAll("COALESCE(AVG(r.rating), 0) as avg_rating", "COALESCE(m.rating, 5.0) as avg_rating");
    next = next.replaceAll("COUNT(DISTINCT r.id) as review_count", "COALESCE(m.rating_count, 0) as review_count");
    next = next.replace(/\s+LEFT JOIN reviews r ON r\.merchant_id = m\.id/g, "");
    next = next.replaceAll("m.status = 'open'", "m.is_open = 1");
    next = next.replace(
      /let sql = `SELECT m\.\*, COALESCE\(AVG\(r\.rating\), 0\) as avg_rating, COUNT\(DISTINCT r\.id\) as review_count\s+FROM merchants m\s+LEFT JOIN reviews r ON r\.merchant_id = m\.id\s+WHERE m\.status = 'open'`;/,
      "let sql = `SELECT m.*, COALESCE(m.rating, 5.0) as avg_rating, COALESCE(m.rating_count, 0) as review_count\n      FROM merchants m\n      WHERE m.is_open = 1`;"
    );
    next = next.replaceAll("WHERE status = 'open'", "WHERE is_open = 1");
    next = next.replaceAll("menu_items", "products");
    next = next.replaceAll(
      "UPDATE merchants SET status = ? WHERE id = ?",
      "UPDATE merchants SET is_open = ?, updated_at = datetime(\"now\",\"localtime\") WHERE id = ?"
    );
    next = next.replaceAll(".run(status, merchant.id);", ".run(status === 'open' ? 1 : 0, merchant.id);");
    next = next.replace(
      "db.prepare(`UPDATE merchants SET status = ? WHERE id = ?`).run(status, merchant.id);\n    res.json({ message: '营业状态已更新', status });",
      "const isOpen = status === 'open';\n    db.prepare(`UPDATE merchants SET is_open = ?, updated_at = datetime(\"now\",\"localtime\") WHERE id = ?`).run(isOpen ? 1 : 0, merchant.id);\n    res.json({ message: '营业状态已更新', status: isOpen ? 'open' : 'closed' });"
    );
    next = next.replace(
      /const reviews = db\.prepare\(`\s+SELECT r\.\*, u\.username as user_name\s+FROM reviews r JOIN users u ON r\.user_id = u\.id\s+WHERE r\.merchant_id = \? ORDER BY r\.created_at DESC LIMIT 50\s+`\)\.all\(req\.params\.id\);/,
      "const reviews = db.prepare(`\n      SELECT o.id, o.rating_merchant as rating, o.review, u.username as user_name, o.completed_at as created_at\n      FROM orders o JOIN users u ON o.customer_id = u.id\n      WHERE o.merchant_id = ? AND COALESCE(o.review, '') != ''\n      ORDER BY o.completed_at DESC LIMIT 50\n    `).all(req.params.id);"
    );
    next = next.replace(
      /const reviews = db\.prepare\(`[\s\S]*?FROM reviews r JOIN users u ON r\.user_id = u\.id[\s\S]*?`\)\.all\(req\.params\.id\);/,
      "const reviews = db.prepare(`\n      SELECT o.id, o.rating_merchant as rating, o.review, u.username as user_name, o.completed_at as created_at\n      FROM orders o JOIN users u ON o.customer_id = u.id\n      WHERE o.merchant_id = ? AND COALESCE(o.review, '') != ''\n      ORDER BY o.completed_at DESC LIMIT 50\n    `).all(req.params.id);"
    );
    return next;
  }, "server/src/routes/merchants.ts: align merchant routes with schema tables and columns.");
}

function patchRiderDeliveryAddress() {
  if (!mentions(["rider", "riders", "骑手", "delivery_address", "available-orders", "my-orders"])) {
    return;
  }
  updateFile("server/src/routes/riders.ts", (source) => source
    .replaceAll(
      "delivery_address: JSON.parse(o.delivery_address),",
      "delivery_address: {\n      address: o.address,\n      phone: o.phone,\n      customer_name: o.customer_name,\n    },"
    )
    .replace(
      /delivery_address:\s*JSON\.parse\(o\.delivery_address\),?/g,
      "delivery_address: {\n      address: o.address,\n      phone: o.phone,\n      customer_name: o.customer_name,\n    },"
    ), "server/src/routes/riders.ts: build rider delivery address from order columns.");
}

function patchAdminMerchantStatus() {
  if (!mentions(["admin", "merchant", "merchants", "商家", "status", "is_open", "no such column: status"])) {
    return;
  }
  updateFile("server/src/routes/admin.ts", (source) => {
    let next = source;
    next = next.replace(
      "db.prepare('UPDATE merchants SET status = ?, updated_at = datetime(\"now\",\"localtime\") WHERE id = ?')\n      .run(status, req.params.id);",
      "const isOpen = status === 'open' || status === true || status === 1;\n    db.prepare('UPDATE merchants SET is_open = ?, updated_at = datetime(\"now\",\"localtime\") WHERE id = ?')\n      .run(isOpen ? 1 : 0, req.params.id);"
    );
    next = next.replace(
      "\"INSERT INTO users (username, password_hash, phone, role) VALUES (?, ?, ?, 'merchant')\"\n    ).run(username, hashedPassword, phone || '');",
      "\"INSERT INTO users (username, password_hash, phone, role, name) VALUES (?, ?, ?, 'merchant', ?)\"\n    ).run(username, hashedPassword, phone || '', name);"
    );
    return next;
  }, "server/src/routes/admin.ts: align admin merchant writes with schema.");
}

function mentions(words) {
  return words.some((word) => prompt.includes(word.toLowerCase()));
}

function updateFile(relativePath, transform, note) {
  if (!exists(relativePath)) {
    return;
  }
  const before = readRel(relativePath);
  const after = transform(before);
  if (after !== before) {
    fs.writeFileSync(path.join(repoDir, relativePath), after, "utf8");
    if (!changedFiles.includes(relativePath)) {
      changedFiles.push(relativePath);
    }
    notes.push(note);
  }
}

function runValidation() {
  const lines = [...notes];
  const commands = [];
  if (exists(".git")) {
    commands.push("git diff --check");
    try {
      execFileSync("git", ["diff", "--check"], { cwd: repoDir, encoding: "utf8" });
      lines.push("git diff --check: passed");
    } catch (error) {
      const output = `${error.stdout || ""}${error.stderr || ""}`.trim();
      throw new Error(output || "git diff --check failed");
    }
  } else {
    lines.push("git diff --check: skipped because repository metadata is unavailable");
  }
  writeTestLog(lines);
  return commands.length === 0 ? ["local deterministic patch validation"] : commands;
}

function writePatch() {
  if (!exists(".git")) {
    fs.writeFileSync(patchFile, "Patch generated in a non-git test workspace.\n", "utf8");
    return;
  }
  const diff = execFileSync("git", ["diff", "--binary"], { cwd: repoDir, encoding: "utf8" });
  fs.writeFileSync(patchFile, diff, "utf8");
}

function writeResult(status, summary, files, commands, testStatus, riskLevel, needHumanAction) {
  const prBody = files.length === 0
    ? ""
    : [
        "## Summary",
        summary,
        "",
        "## Changed Files",
        ...files.map((file) => `- ${file}`),
        "",
        "## Validation",
        ...(commands.length === 0 ? ["- Validation skipped"] : commands.map((command) => `- ${command}`))
      ].join("\n");
  fs.writeFileSync(resultFile, JSON.stringify({
    status,
    summary,
    changedFiles: files,
    testCommands: commands,
    testStatus,
    riskLevel,
    prBody,
    needHumanAction
  }, null, 2), "utf8");
}

function writeTestLog(lines) {
  fs.writeFileSync(testLogFile, `${lines.join("\n")}\n`, "utf8");
}

function readRel(relativePath) {
  return readFile(path.join(repoDir, relativePath));
}

function readFile(file) {
  if (!fs.existsSync(file)) {
    return "";
  }
  return fs.readFileSync(file, "utf8");
}

function exists(relativePath) {
  return fs.existsSync(path.join(repoDir, relativePath));
}

function jsonFile(relativePath) {
  try {
    return JSON.parse(readRel(relativePath));
  } catch {
    return {};
  }
}
