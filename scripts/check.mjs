import { readdir } from "node:fs/promises";
import { extname, join } from "node:path";
import { spawnSync } from "node:child_process";

const roots = ["apps", "packages", "examples", "tests", "scripts"];

async function findJavaScript(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) files.push(...await findJavaScript(path));
    if (entry.isFile() && [".js", ".mjs"].includes(extname(entry.name))) files.push(path);
  }
  return files;
}

const files = (await Promise.all(roots.map(findJavaScript))).flat();
for (const file of files) {
  const result = spawnSync(process.execPath, ["--check", file], { stdio: "inherit" });
  if (result.status !== 0) process.exit(result.status ?? 1);
}
console.log(`Syntax checked ${files.length} JavaScript modules.`);
