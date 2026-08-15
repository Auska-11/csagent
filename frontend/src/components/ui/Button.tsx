import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
  icon?: ReactNode;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'secondary', icon, className = '', children, ...props },
  ref,
) {
  return (
    <button ref={ref} className={`button button--${variant} ${className}`} {...props}>
      {icon}
      {children}
    </button>
  );
});
