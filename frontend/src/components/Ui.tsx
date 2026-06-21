import { forwardRef, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from "react";

import { cn } from "../utils";

export function Button({
  className,
  variant = "default",
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "default" | "primary" | "ghost" | "danger" }) {
  return <button className={cn("ui-button", `ui-button--${variant}`, className)} {...props} />;
}

export function Badge({
  children,
  tone = "neutral"
}: {
  children: ReactNode;
  tone?: "neutral" | "success" | "warning" | "danger" | "info";
}) {
  return <span className={cn("ui-badge", `ui-badge--${tone}`)}>{children}</span>;
}

export function Card({
  title,
  description,
  action,
  children,
  className
}: {
  title?: ReactNode;
  description?: ReactNode;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={cn("ui-card", className)}>
      {(title || description || action) && (
        <div className="ui-card-header">
          <div>
            {title && <h2 className="ui-card-title">{title}</h2>}
            {description && <p className="ui-card-description">{description}</p>}
          </div>
          {action && <div className="admin-page-actions">{action}</div>}
        </div>
      )}
      <div className="ui-card-content">{children}</div>
    </section>
  );
}

export function Field({
  label,
  children,
  full
}: {
  label: string;
  children: ReactNode;
  full?: boolean;
}) {
  return (
    <label className={cn("form-field", full && "form-field--full")}>
      <span>{label}</span>
      {children}
    </label>
  );
}

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(function Input(props, ref) {
  return <input ref={ref} className={cn("ui-input", props.className)} {...props} />;
});

export function Textarea(props: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea className={cn("ui-textarea", props.className)} {...props} />;
}

export function Select(props: SelectHTMLAttributes<HTMLSelectElement>) {
  return <select className={cn("ui-input", props.className)} {...props} />;
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty-state">{children}</div>;
}

export function PageHeader({
  title,
  description,
  action
}: {
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="admin-page-header">
      <div>
        <h1 className="admin-page-title">{title}</h1>
        <p className="admin-page-subtitle">{description}</p>
      </div>
      {action && <div className="admin-page-actions">{action}</div>}
    </div>
  );
}

export function Table({
  headers,
  children,
  minWidth = 760
}: {
  headers: string[];
  children: ReactNode;
  minWidth?: number;
}) {
  return (
    <div className="ui-table-wrap">
      <table className="ui-table" style={{ minWidth }}>
        <thead className="ui-table-header">
          <tr>{headers.map((header) => <th key={header}>{header}</th>)}</tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}
