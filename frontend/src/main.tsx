import React from "react";
import { createRoot } from "react-dom/client";

import { App } from "./App";
import "./styles.css";

document.documentElement.dataset.adminBoot = "createRoot";
document.documentElement.dataset.apiClientMarker = "const api";

const root = document.getElementById("root");
if (!root) {
  throw new Error("React root container missing");
}

createRoot(root).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
