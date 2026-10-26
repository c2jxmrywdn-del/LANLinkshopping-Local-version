"use client";

import React, { useRef, useState, MouseEvent } from "react";
import { motion, useMotionTemplate, useMotionValue, useSpring } from "motion/react";
import { cn } from "@/lib/utils";

export interface LiquidGlassCardProps extends React.HTMLAttributes<HTMLDivElement> {
  children?: React.ReactNode;
  className?: string;
  glowColor?: string;
  spotlightSize?: number;
  tiltAngle?: number;
  highlightBorder?: boolean;
}

export const LiquidGlassCard: React.FC<LiquidGlassCardProps> = ({
  children,
  className,
  glowColor = "rgba(255, 255, 255, 0.18)",
  spotlightSize = 350,
  tiltAngle = 10,
  highlightBorder = true,
  ...props
}) => {
  const cardRef = useRef<HTMLDivElement>(null);
  const [isHovered, setIsHovered] = useState(false);

  const mouseX = useMotionValue(0);
  const mouseY = useMotionValue(0);

  const rotateX = useSpring(0, { stiffness: 220, damping: 20 });
  const rotateY = useSpring(0, { stiffness: 220, damping: 20 });

  function handleMouseMove(e: MouseEvent<HTMLDivElement>) {
    if (!cardRef.current) return;
    const rect = cardRef.current.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;

    mouseX.set(x);
    mouseY.set(y);

    const centerX = rect.width / 2;
    const centerY = rect.height / 2;
    const tiltX = ((y - centerY) / centerY) * -tiltAngle;
    const tiltY = ((x - centerX) / centerX) * tiltAngle;

    rotateX.set(tiltX);
    rotateY.set(tiltY);
  }

  function handleMouseLeave() {
    setIsHovered(false);
    rotateX.set(0);
    rotateY.set(0);
  }

  function handleMouseEnter() {
    setIsHovered(true);
  }

  const spotlightBackground = useMotionTemplate`
    radial-gradient(
      ${spotlightSize}px circle at ${mouseX}px ${mouseY}px,
      ${glowColor},
      transparent 80%
    )
  `;

  return (
    <div
      style={{ perspective: "1000px" }}
      className="inline-block w-full transition-transform duration-300"
    >
      <motion.div
        ref={cardRef}
        onMouseMove={handleMouseMove}
        onMouseEnter={handleMouseEnter}
        onMouseLeave={handleMouseLeave}
        style={{
          rotateX,
          rotateY,
          transformStyle: "preserve-3d",
        }}
        className={cn(
          "relative overflow-hidden rounded-3xl p-6",
          "bg-white/40 dark:bg-white/[0.04]",
          "backdrop-blur-2xl saturate-[180%]",
          "border border-white/20 dark:border-white/10",
          "shadow-[0_8px_32px_0_rgba(0,0,0,0.12),inset_0_1px_1px_0_rgba(255,255,255,0.4)]",
          "transition-all duration-300 hover:shadow-[0_16px_48px_0_rgba(0,0,0,0.2),inset_0_1px_1px_0_rgba(255,255,255,0.6)]",
          className
        )}
        {...props}
      >
        {/* Specular light highlight layer */}
        <motion.div
          className="pointer-events-none absolute -inset-px rounded-3xl opacity-0 transition-opacity duration-300 group-hover:opacity-100"
          style={{
            background: spotlightBackground,
            opacity: isHovered ? 1 : 0,
          }}
        />

        {/* Refractive inner edge highlight */}
        {highlightBorder && (
          <div className="pointer-events-none absolute inset-0 rounded-3xl ring-1 ring-inset ring-white/20 dark:ring-white/10" />
        )}

        {/* Content slot */}
        <div className="relative z-10">{children}</div>
      </motion.div>
    </div>
  );
};
