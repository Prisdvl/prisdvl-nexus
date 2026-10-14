'use client';

import React, { useEffect, useLayoutEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { animate, motion, useMotionValue, useReducedMotion, type MotionValue } from 'motion/react';

import './RubberSegment.css';

export type RubberSegmentSize = 'sm' | 'md' | 'lg';
export type RubberSegmentItem = string | { value: string; label: ReactNode; icon?: ReactNode };

export interface RubberSegmentProps {
  items: RubberSegmentItem[];
  value?: string;
  defaultValue?: string;
  onChange?: (value: string, index: number) => void;
  trackColor?: string;
  thumbColor?: string;
  textColor?: string;
  activeTextColor?: string;
  size?: RubberSegmentSize;
  radius?: number;
  inset?: number;
  equalSlots?: boolean;
  stretch?: number;
  squash?: number;
  speed?: number;
  glide?: number;
  draggable?: boolean;
  /** [原型扩展] 轴向：竖向用于侧栏导航 */
  direction?: 'horizontal' | 'vertical';
  /** [原型扩展] 外部改 value 时也走橡皮滑动（默认跳变）——scroll-spy 高亮跟随需要它 */
  animateExternal?: boolean;
  disabled?: boolean;
  className?: string;
  'aria-label'?: string;
}

type Slot = { l: number; r: number };
type Sample = [number, number];
type Drag = {
  id: number;
  x0: number;
  slot: number;
  onThumb: boolean;
  live: boolean;
  offset: number;
  w: number;
  hist: Sample[];
};

const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];
const SPRING_UI = { type: 'spring' as const, duration: 0.3, bounce: 0 };
const SPRING_MOMENTUM = { type: 'spring' as const, duration: 0.4, bounce: 0.2 };
const SPRING_RELAX = { type: 'spring' as const, duration: 0.16, bounce: 0 };
const DILATE = 0.19;
const HANDOFF = 0.15;
const FLICK = 110;
const MAX_VELOCITY = 2000;
const DEADZONE = 4;
const SLOP = 10;
const RUBBER = 0.55;
const SIZES: Record<RubberSegmentSize, { height: number; font: number; pad: number; min: number }> = {
  sm: { height: 28, font: 12, pad: 10, min: 36 },
  md: { height: 36, font: 13, pad: 14, min: 44 },
  lg: { height: 44, font: 14, pad: 18, min: 48 }
};

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value));
const rubber = (over: number, dim: number) => (over * dim * RUBBER) / (dim + RUBBER * Math.abs(over));
const project = (v: number, glide: number) => {
  const d = 1 - 0.1 * Math.pow(0.05, glide / 100);
  return ((v / 1000) * d) / (1 - d);
};
const velocityOf = (hist: Sample[], now: number) => {
  const recent = hist.filter(([t]) => now - t <= 100);
  if (recent.length < 2) return 0;
  const [t0, x0] = recent[0];
  const [t1, x1] = recent[recent.length - 1];
  return t1 - t0 >= 8 ? ((x1 - x0) / (t1 - t0)) * 1000 : 0;
};
const nearestSlot = (slots: Slot[], x: number) => {
  let best = 0;
  for (let i = 1; i < slots.length; i++) {
    if (Math.abs((slots[i].l + slots[i].r) / 2 - x) < Math.abs((slots[best].l + slots[best].r) / 2 - x)) best = i;
  }
  return best;
};

const RubberSegment: React.FC<RubberSegmentProps> = ({
  items,
  value,
  defaultValue,
  onChange,
  trackColor = '#27272a',
  thumbColor = '#fafafa',
  textColor = '#fafafa',
  activeTextColor = '#18181b',
  size = 'md',
  radius = 10,
  inset = 3,
  equalSlots = true,
  stretch = 100,
  squash = 3,
  speed = 1,
  glide = 75,
  draggable = true,
  direction = 'horizontal',
  animateExternal = false,
  disabled = false,
  className = '',
  'aria-label': ariaLabel = 'Segmented control'
}) => {
  const list = items.map(item => (typeof item === 'string' ? { value: item, label: item } : item));
  const [inner, setInner] = useState<string | undefined>(defaultValue ?? list[0]?.value);
  const current = value !== undefined ? value : inner;
  const index = Math.max(
    0,
    list.findIndex(item => item.value === current)
  );
  const reduce = useReducedMotion();
  const vertical = direction === 'vertical';

  const trackRef = useRef<HTMLDivElement>(null);
  const itemRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const slots = useRef<Slot[]>([]);
  const box = useRef<DOMRect | null>(null);
  const committed = useRef(index);
  const handoff = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const drag = useRef<Drag | null>(null);
  const gen = useRef(0);
  /* travel/land 进行中：禁止 measure() 里 jumpTo，否则跨项滑动会被 ResizeObserver 掐成瞬移 */
  const moving = useRef(false);

  const edgeL = useMotionValue(0);
  const edgeR = useMotionValue(0);
  const innerW = useMotionValue(0);

  const thumbRadius = Math.max(0, radius - inset);
  /*
   * 手写订阅，而不是 `useTransform(() => …)`。
   *
   * 实测（埋点 trace）：useTransform 的**函数形态**在本组件里不会随 motion value 变化重算 ——
   * 它只在挂载时（三个值都还是 0）算过几次，之后 measure()/jumpTo() 写入正确值也不再触发，
   * clip 就停在 inset(0)（四边为 0 = 整个轨道露出），视觉上是"所有选项同时被选中"。
   * 组件被条件渲染挂载时（设置页切换分区）必现。
   *
   * 显式 on('change') 订阅 + 自己 set clipPath：行为确定，且保留逐帧动画
   * （拖动与回弹过程中 edge 每帧变化都会重算）。
   */
  const clipPath = useMotionValue<string>(`inset(0px round ${thumbRadius}px)`);
  useEffect(() => {
    const update = () => {
      const inner = innerW.get();
      const L = Math.max(0, edgeL.get());
      const R = Math.max(0, edgeR.get());
      clipPath.set(
        vertical
          ? `inset(${L}px 0 ${Math.max(0, inner - R)}px 0 round ${thumbRadius}px)`
          : `inset(0 ${Math.max(0, inner - R)}px 0 ${L}px round ${thumbRadius}px)`
      );
    };
    update();
    const unsubs = [innerW, edgeL, edgeR].map((mv) => mv.on('change', update));
    return () => unsubs.forEach((u) => u());
  }, [innerW, edgeL, edgeR, clipPath, vertical, thumbRadius]);

  const t = (seconds: number) => seconds / speed;

  const jumpTo = (i: number) => {
    const s = slots.current[i];
    if (!s) return;
    clearTimeout(handoff.current);
    gen.current += 1;
    moving.current = false;
    edgeL.jump(s.l);
    edgeR.jump(s.r);
  };

  const measure = () => {
    const track = trackRef.current;
    if (!track) return;
    const rect = track.getBoundingClientRect();
    box.current = rect;
    slots.current = list.map((_, i) => {
      const el = itemRefs.current[i];
      if (!el) return { l: 0, r: 0 };
      const r = el.getBoundingClientRect();
      return vertical
        ? { l: r.top - rect.top - inset, r: r.bottom - rect.top - inset }
        : { l: r.left - rect.left - inset, r: r.right - rect.left - inset };
    });
    innerW.set((vertical ? rect.height : rect.width) - inset * 2);
    if (drag.current || moving.current) return;
    jumpTo(committed.current);
  };

  const listKey = list.map(item => item.value).join('|');
  useLayoutEffect(() => {
    let alive = true;
    const update = () => { if (alive) measure(); };
    update();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(update);
    if (trackRef.current) observer?.observe(trackRef.current);
    itemRefs.current.forEach((item) => { if (item) observer?.observe(item); });
    const fonts = typeof document !== 'undefined' ? document.fonts : undefined;
    fonts?.addEventListener?.('loadingdone', update);
    void fonts?.ready.then(update);
    window.addEventListener('resize', update);
    window.visualViewport?.addEventListener('resize', update);
    return () => {
      alive = false;
      observer?.disconnect();
      fonts?.removeEventListener?.('loadingdone', update);
      window.removeEventListener('resize', update);
      window.visualViewport?.removeEventListener('resize', update);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [listKey, size, inset, equalSlots, vertical]);

  useEffect(() => {
    if (drag.current || committed.current === index) return;
    const from = committed.current;
    committed.current = index;
    if (animateExternal && !reduce) travel(from, index);
    else jumpTo(index);
  });

  useEffect(
    () => () => {
      clearTimeout(handoff.current);
      edgeL.stop();
      edgeR.stop();
    },
    [edgeL, edgeR]
  );

  const commit = (i: number) => {
    committed.current = i;
    if (i === index) return;
    if (value === undefined) setInner(list[i].value);
    onChange?.(list[i].value, i);
  };

  const land = (to: number, v: number | null, flick: boolean, withSquash: boolean) => {
    const b = slots.current[to];
    if (!b) return;
    const g = ++gen.current;
    moving.current = true;
    const done = () => {
      if (gen.current === g) moving.current = false;
    };
    const dir = Math.sign((b.l + b.r) / 2 - (edgeL.get() + edgeR.get()) / 2) || 1;
    const [lead, leadTo, trail, trailTo] = dir > 0 ? [edgeR, b.r, edgeL, b.l] : [edgeL, b.l, edgeR, b.r];
    const velocityFor = (mv: MotionValue<number>) =>
      clamp(v === null ? mv.getVelocity() : v, -MAX_VELOCITY, MAX_VELOCITY);
    const leadAnim = animate(lead, leadTo, {
      ...(flick ? SPRING_MOMENTUM : SPRING_UI),
      duration: t(flick ? 0.4 : 0.3),
      velocity: velocityFor(lead)
    });
    const trailVelocity = velocityFor(trail);
    if (!withSquash || squash <= 0) {
      void Promise.all([
        leadAnim,
        animate(trail, trailTo, { ...SPRING_UI, duration: t(0.3), velocity: trailVelocity }),
      ]).then(done);
      return;
    }
    void Promise.all([
      leadAnim,
      animate(trail, trailTo + dir * squash, { ...SPRING_UI, duration: t(0.3), velocity: trailVelocity }).then(() => {
        if (gen.current === g) return animate(trail, trailTo, { ...SPRING_RELAX, duration: t(0.16) });
      }),
    ]).then(done);
  };

  const travel = (from: number, to: number) => {
    const a = slots.current[from];
    const b = slots.current[to];
    if (!a || !b || from === to) return;
    clearTimeout(handoff.current);
    gen.current += 1;
    edgeL.stop();
    edgeR.stop();
    if (reduce) {
      moving.current = false;
      edgeL.jump(b.l);
      edgeR.jump(b.r);
      return;
    }
    moving.current = true;
    const gap = Math.abs(to - from);
    /*
     * 跨项（隔 1+）：定宽平移扫过中间项。
     * 若仍用邻项那套「拉满 from↔to 再收回」，整段轨道会闪一下高亮，看起来像没有滑动。
     */
    if (gap > 1) {
      const g = gen.current;
      const duration = t(0.32 + 0.1 * (gap - 1));
      const tween = { duration, ease: EASE_OUT };
      void Promise.all([animate(edgeL, b.l, tween), animate(edgeR, b.r, tween)]).then(() => {
        if (gen.current === g) moving.current = false;
      });
      return;
    }
    /* 邻项：橡皮拉伸 → land */
    const u = stretch / 100;
    const tween = { duration: t(DILATE), ease: EASE_OUT };
    animate(edgeL, b.l + (Math.min(a.l, b.l) - b.l) * u, tween);
    animate(edgeR, b.r + (Math.max(a.r, b.r) - b.r) * u, tween);
    handoff.current = setTimeout(() => land(to, null, false, true), t(HANDOFF) * 1000);
  };

  const localX = (e: { clientX: number; clientY: number }) =>
    (vertical ? e.clientY - (box.current ? box.current.top : 0) : e.clientX - (box.current ? box.current.left : 0)) - inset;

  const finishClick = (pointerId: number, clientX: number, clientY: number) => {
    const d = drag.current;
    if (!d || d.id !== pointerId || d.live) return;
    release();
    const x = localX({ clientX, clientY });
    if (Math.abs(x - d.x0) > SLOP || d.slot === committed.current) return;
    const from = committed.current;
    commit(d.slot);
    travel(from, d.slot);
  };

  const handlePointerDown = (e: React.PointerEvent<HTMLButtonElement>, i: number) => {
    if (disabled || drag.current || e.button !== 0) return;
    box.current = trackRef.current?.getBoundingClientRect() ?? null;
    const x = localX(e);
    const onThumb = draggable && x >= edgeL.get() && x <= edgeR.get();
    drag.current = { id: e.pointerId, x0: x, slot: i, onThumb, live: false, offset: 0, w: 0, hist: [[e.timeStamp, x]] };
    /*
     * 只在拖滑块时 capture。点击切换若也 capture，lostpointercapture 会与
     * 冒泡到轨道的 pointerup 抢跑，把 drag 清掉 → travel 根本进不去，只剩受控
     * value 更新后的 jump/闪切，跨项看起来「没有滑动」。
     */
    if (onThumb) {
      try {
        e.currentTarget.setPointerCapture(e.pointerId);
      } catch {}
      clearTimeout(handoff.current);
      gen.current += 1;
      moving.current = false;
      edgeL.stop();
      edgeR.stop();
    } else {
      if (!reduce) e.currentTarget.dataset.pressed = '';
      /* 未 capture：松手可能在轨道外，用 window 收尾保证跨项 travel 一定触发 */
      const pointerId = e.pointerId;
      const onWinUp = (ev: PointerEvent) => {
        if (ev.pointerId !== pointerId) return;
        window.removeEventListener('pointerup', onWinUp, true);
        window.removeEventListener('pointercancel', onWinCancel, true);
        finishClick(pointerId, ev.clientX, ev.clientY);
      };
      const onWinCancel = (ev: PointerEvent) => {
        if (ev.pointerId !== pointerId) return;
        window.removeEventListener('pointerup', onWinUp, true);
        window.removeEventListener('pointercancel', onWinCancel, true);
        const d = drag.current;
        if (d && d.id === pointerId && !d.live) release();
      };
      window.addEventListener('pointerup', onWinUp, true);
      window.addEventListener('pointercancel', onWinCancel, true);
    }
  };

  const handlePointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const d = drag.current;
    if (!d || e.pointerId !== d.id || !d.onThumb) return;
    const x = localX(e);
    d.hist.push([e.timeStamp, x]);
    if (d.hist.length > 8) d.hist.shift();
    if (!d.live) {
      if (Math.abs(x - d.x0) < DEADZONE) return;
      d.live = true;
      d.offset = x - edgeL.get();
      d.w = edgeR.get() - edgeL.get();
      if (trackRef.current) trackRef.current.dataset.held = '';
    }
    const width = innerW.get();
    const l = x - d.offset;
    const maxL = width - d.w;
    if (reduce) {
      const c = clamp(l, 0, maxL);
      edgeL.set(c);
      edgeR.set(c + d.w);
    } else if (l < 0) {
      edgeL.set(0);
      edgeR.set(d.w - rubber(-l, d.w));
    } else if (l > maxL) {
      edgeR.set(width);
      edgeL.set(maxL + rubber(l - maxL, d.w));
    } else {
      edgeL.set(l);
      edgeR.set(l + d.w);
    }
  };

  const release = () => {
    const d = drag.current as Drag;
    drag.current = null;
    if (trackRef.current) delete trackRef.current.dataset.held;
    const el = itemRefs.current[d.slot];
    if (el) delete el.dataset.pressed;
    return d;
  };

  const handlePointerUp = (e: React.PointerEvent<HTMLDivElement>) => {
    const d = drag.current;
    if (!d || e.pointerId !== d.id) return;
    /* 非拖动点击由 window pointerup（finishClick）处理，避免与这里双发 travel */
    if (!d.live) {
      if (!d.onThumb) return;
      finishClick(e.pointerId, e.clientX, e.clientY);
      return;
    }
    release();
    const v = velocityOf(d.hist, e.timeStamp);
    const flick = Math.abs(v) > FLICK;
    let to = nearestSlot(slots.current, (edgeL.get() + edgeR.get()) / 2 + project(v, glide));
    if (flick && to === committed.current) to = clamp(to + Math.sign(v), 0, list.length - 1);
    commit(to);
    if (reduce) jumpTo(to);
    else land(to, v, flick, flick);
  };

  const handlePointerCancel = (e: React.PointerEvent<HTMLDivElement>) => {
    const d = drag.current;
    if (!d || e.pointerId !== d.id) return;
    /* 未进入拖动的点击交给 pointerup 走 travel；这里只收尾真·拖动中断 */
    if (!d.live) return;
    release();
    if (reduce) jumpTo(committed.current);
    else land(committed.current, null, false, false);
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLButtonElement>) => {
    if (disabled) return;
    const last = list.length - 1;
    let next: number | null = null;
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') next = Math.min(last, index + 1);
    else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') next = Math.max(0, index - 1);
    else if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = last;
    if (next === null) return;
    e.preventDefault();
    if (next === index) return;
    const from = committed.current;
    commit(next);
    if (reduce) jumpTo(next);
    else travel(from, next);
    itemRefs.current[next]?.focus();
  };

  const preset = SIZES[size] || SIZES.md;

  return (
    <div
      ref={trackRef}
      role="radiogroup"
      aria-label={ariaLabel}
      aria-disabled={disabled || undefined}
      data-equal={equalSlots ? '' : undefined}
      data-vertical={vertical ? '' : undefined}
      data-draggable={draggable && !disabled ? '' : undefined}
      className={`rubber-segment${className ? ` ${className}` : ''}`}
      style={
        {
          '--rs-track': trackColor,
          '--rs-thumb': thumbColor,
          '--rs-ink': textColor,
          '--rs-ink-active': activeTextColor,
          '--rs-radius': `${radius}px`,
          '--rs-inset': `${inset}px`,
          '--rs-thumb-radius': `${thumbRadius}px`,
          '--rs-h': `${preset.height}px`,
          '--rs-font': `${preset.font}px`,
          '--rs-pad': `${preset.pad}px`,
          '--rs-min': `${preset.min}px`
        } as CSSProperties
      }
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      onPointerCancel={handlePointerCancel}
      onLostPointerCapture={handlePointerCancel}
    >
      {list.map((item, i) => (
        <button
          key={item.value}
          ref={el => {
            itemRefs.current[i] = el;
          }}
          type="button"
          role="radio"
          aria-checked={i === index}
          tabIndex={i === index ? 0 : -1}
          disabled={disabled}
          className="rubber-segment__item"
          onPointerDown={e => handlePointerDown(e, i)}
          onKeyDown={handleKeyDown}
        >
          {item.icon}
          {item.label}
        </button>
      ))}
      <motion.div className="rubber-segment__thumb" aria-hidden="true" style={{ clipPath }}>
        {list.map(item => (
          <span key={item.value} className="rubber-segment__item rubber-segment__copy">
            {item.icon}
            {item.label}
          </span>
        ))}
      </motion.div>
    </div>
  );
};

export default RubberSegment;
