import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "motion/react";

export default function Toast({ toast }) {
  const [shown, setShown] = useState(null);
  useEffect(() => {
    if (!toast) return;
    setShown(toast);
    const t = setTimeout(() => setShown(null), 2200);
    return () => clearTimeout(t);
  }, [toast]);
  return (
    <AnimatePresence>
      {shown && (
        <motion.div
          key={shown.key}
          initial={{ y: 30, opacity: 0 }} animate={{ y: 0, opacity: 1 }} exit={{ y: 30, opacity: 0 }}
          className="fixed z-50 bottom-24 inset-x-0 mx-auto w-fit max-w-[90%] px-4 py-2.5 rounded-full bg-raised text-ink t-meta shadow-lg"
        >
          {shown.msg}
        </motion.div>
      )}
    </AnimatePresence>
  );
}
