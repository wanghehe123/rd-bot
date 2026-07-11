import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import ts from "typescript";

test("mounts the Sonner Toaster in the application root", () => {
  const source = readFileSync(new URL("../src/App.tsx", import.meta.url), "utf8");
  const sourceFile = ts.createSourceFile(
    "App.tsx",
    source,
    ts.ScriptTarget.Latest,
    true,
    ts.ScriptKind.TSX
  );
  let importsToaster = false;
  let rendersToaster = false;

  const visit = (node: ts.Node) => {
    if (ts.isImportDeclaration(node)
        && ts.isStringLiteral(node.moduleSpecifier)
        && node.moduleSpecifier.text === "sonner") {
      const bindings = node.importClause?.namedBindings;
      importsToaster = Boolean(bindings && ts.isNamedImports(bindings)
        && bindings.elements.some((item) => item.name.text === "Toaster"));
    }
    if (ts.isJsxSelfClosingElement(node) && node.tagName.getText(sourceFile) === "Toaster") {
      rendersToaster = true;
    }
    ts.forEachChild(node, visit);
  };
  visit(sourceFile);

  assert.equal(importsToaster, true);
  assert.equal(rendersToaster, true);
});
