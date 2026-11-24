import { forwardRef, useEffect, useImperativeHandle, useRef } from "react";
import { animate, motion, useMotionValue, useTransform } from "motion/react";
import Card from "./Card.jsx";
import { haptic } from "../api.js";

const EXIT_MS = 0.18;

/**
 * One draggable card. Right = more like this, left = seen, down = less like this.
 * Release past ~35% of the width (or flick) to commit; otherwise it springs back.
 */
const SwipeCard = forwardRef(function SwipeCard({ card, onSwipe, onTap, onDoubleTap, onDragState, onChip, onWhy, enterFrom }, ref) {
  const box = useRef(null);
  const x = useMotionValue(enterFrom === "left" ? -600 : enterFrom === "right" ? 600 : 0);
  const y = useMotionValue(enterFrom === "down" ? 800 : 0);
  const rotate = useTransform(x, (v) => {
    const w = box.current?.offsetWidth || 360;
    return Math.max(-8, Math.min(8, (v / w) * 8));
  });
  const moreOpacity = useTransform(x, [20, 120], [0, 1]);
  const seenOpacity = useTransform(x, [-120, -20], [1, 0]);
  const lessOpacity = useTransform(y, [20, 140], [0, 1]);
  const committed = useRef(false);
  const lastTap = useRef(0);
  const tapTimer = useRef(null);
  const moved = useRef(false);

  // Undo flies the card back in with the same spring.
  useEffect(() => {
    if (!enterFrom) return;
    animate(x, 0, { type: "spring", stiffness: 400, damping: 34 });
    animate(y, 0, { type: "spring", stiffness: 400, damping: 34 });
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const fly = (dir) => {
    if (committed.current) return;
    committed.current = true;
    haptic(12);
    const w = box.current?.offsetWidth || 400;
    const h = box.current?.offsetHeight || 800;
    const target = dir === "right" ? { x: w * 1.5, y: y.get() } : dir === "left" ? { x: -w * 1.5, y: y.get() } : { x: x.get(), y: h * 1.3 };
    animate(x, target.x, { duration: EXIT_MS, ease: "easeIn" });
    animate(y, target.y, { duration: EXIT_MS, ease: "easeIn" }).then(() => onSwipe(dir));
  };

  useImperativeHandle(ref, () => ({ fly }));

  return (
    <motion.div
      ref={box}
      className="absolute inset-0 touch-none cursor-grab active:cursor-grabbing"
      style={{ x, y, rotate }}
      drag
      dragElastic={0.9}
      dragMomentum={false}
      dragConstraints={{ left: 0, right: 0, top: 0, bottom: 0 }}
      onDragStart={() => { moved.current = true; onDragState?.(true); }}
      onDragEnd={(_, info) => {
        onDragState?.(false);
        const w = box.current?.offsetWidth || 400;
        const h = box.current?.offsetHeight || 800;
        const { offset, velocity } = info;
        const horizontal = Math.abs(offset.x) > Math.abs(offset.y);
        if (horizontal && (offset.x > w * 0.35 || velocity.x > 700)) return fly("right");
        if (horizontal && (offset.x < -w * 0.35 || velocity.x < -700)) return fly("left");
        if (!horizontal && (offset.y > h * 0.22 || velocity.y > 800)) return fly("down");
        animate(x, 0, { type: "spring", stiffness: 500, damping: 32 });
        animate(y, 0, { type: "spring", stiffness: 500, damping: 32 });
        setTimeout(() => (moved.current = false), 50);
      }}
      onPointerDown={() => (moved.current = false)}
      onClick={() => {
        if (moved.current) return;
        const now = Date.now();
        if (now - lastTap.current < 280) {
          clearTimeout(tapTimer.current);
          lastTap.current = 0;
          onDoubleTap?.();
          return;
        }
        lastTap.current = now;
        tapTimer.current = setTimeout(() => onTap?.(), 280);
      }}
    >
      <Card card={card} onChip={onChip} onWhy={onWhy} />
      {/* Swipe feedback labels fade in with drag distance */}
      <motion.div style={{ opacity: moreOpacity }} className="pointer-events-none absolute top-6 left-6 px-3 py-1.5 rounded-lg border-2 border-more text-more t-meta font-bold bg-black/40 -rotate-6">
        More like this
      </motion.div>
      <motion.div style={{ opacity: seenOpacity }} className="pointer-events-none absolute top-6 right-6 px-3 py-1.5 rounded-lg border-2 border-seen text-seen t-meta font-bold bg-black/40 rotate-6">
        Seen
      </motion.div>
      <motion.div style={{ opacity: lessOpacity }} className="pointer-events-none absolute top-6 inset-x-0 mx-auto w-fit px-3 py-1.5 rounded-lg border-2 border-less text-less t-meta font-bold bg-black/40">
        Less like this
      </motion.div>
    </motion.div>
  );
});

export default SwipeCard;
