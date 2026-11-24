import { useEffect, useRef } from "react";
import { motion } from "motion/react";
import SwipeCard from "./SwipeCard.jsx";
import Card from "./Card.jsx";

/** Preload upcoming images so there is never a blank flash. */
function usePreload(cards) {
  useEffect(() => {
    cards.slice(0, 6).forEach((c) => {
      if (c.image) {
        const img = new Image();
        img.referrerPolicy = "no-referrer";
        img.src = c.image;
      }
    });
  }, [cards]);
}

/**
 * The current card on top, the next one peeking behind at 96% so you always see there is more.
 * `stackRef.current.fly(dir)` lets buttons and arrow keys swipe programmatically.
 */
export default function Stack({ cards, stackRef, onSwiped, onTap, onDoubleTap, onDragState, onChip, onWhy, enterFrom, empty }) {
  usePreload(cards);
  const shownAt = useRef(Date.now());
  const current = cards[0];
  const next = cards[1];

  useEffect(() => {
    shownAt.current = Date.now();
  }, [current?.id]);

  if (!current) return empty;

  return (
    <div className="relative h-full w-full">
      {next && (
        <motion.div
          key={`peek-${next.id}`}
          className="absolute inset-0 pointer-events-none"
          initial={{ scale: 0.92, y: 18, opacity: 0.6 }}
          animate={{ scale: 0.96, y: 12, opacity: 1 }}
          transition={{ duration: 0.2 }}
        >
          <div className="h-full w-full opacity-70"><Card card={next} /></div>
        </motion.div>
      )}
      <motion.div
        key={`cur-${current.id}`}
        className="absolute inset-0"
        initial={enterFrom ? false : { scale: 0.96, y: 12 }}
        animate={{ scale: 1, y: 0 }}
        transition={{ type: "spring", stiffness: 420, damping: 34 }}
      >
        <SwipeCard
          ref={stackRef}
          card={current}
          enterFrom={enterFrom}
          onSwipe={(dir) => onSwiped(current, dir, Date.now() - shownAt.current)}
          onTap={() => onTap?.(current, Date.now() - shownAt.current)}
          onDoubleTap={() => onDoubleTap?.(current)}
          onDragState={onDragState}
          onChip={onChip}
          onWhy={onWhy}
        />
      </motion.div>
    </div>
  );
}
