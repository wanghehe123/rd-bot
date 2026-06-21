import { useEffect, useState } from "react";

import type { ToastTone } from "../types";

type ToastItem = {
  id: number;
  message: string;
  tone: ToastTone;
};

export function ToastHost() {
  const [items, setItems] = useState<ToastItem[]>([]);

  useEffect(() => {
    const handler = (event: Event) => {
      const detail = (event as CustomEvent<{ message: string; tone: ToastTone }>).detail;
      const id = Date.now() + Math.random();
      setItems((current) => [...current, { id, message: detail.message, tone: detail.tone || "info" }]);
      window.setTimeout(() => {
        setItems((current) => current.filter((item) => item.id !== id));
      }, 2600);
    };
    window.addEventListener("rd-bot-toast", handler);
    return () => window.removeEventListener("rd-bot-toast", handler);
  }, []);

  return (
    <div className="toast-stack">
      {items.map((item) => (
        <div key={item.id} className={`toast toast--${item.tone}`}>
          {item.message}
        </div>
      ))}
    </div>
  );
}
