import React from "react";
import { createRoot } from "react-dom/client";
import { AuthRoot } from "./Platform.jsx";
import "./platform.css";
import "./styles.css";
import "./layout.css";
import "./playerRoster.css";
import "./extensions.css";
import "@fontsource-variable/inter";

createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <AuthRoot />
  </React.StrictMode>,
);
