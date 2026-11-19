// 跟随底部：内容变高不算读者离开，往上滚才算。

import { act, cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { useScrollFollow } from "./useScrollFollow";

afterEach(cleanup);

let stick = true;

function Harness({ changed }: { changed: number }) {
  const follow = useScrollFollow(changed, false, () => Promise.resolve());
  stick = follow.stick;
  return (
    <div data-testid="scroller" ref={follow.scroller} onScroll={follow.onScroll}>
      <div ref={follow.content} />
    </div>
  );
}

/** jsdom 不排版：视区 600 高，内容高度由测试给。 */
function mount(height: number) {
  const view = render(<Harness changed={0} />);
  const element = view.getByTestId("scroller");
  const size = { height };
  Object.defineProperty(element, "clientHeight", { get: () => 600 });
  Object.defineProperty(element, "scrollHeight", { get: () => size.height });
  const scroll = (top?: number) =>
    act(() => {
      if (top !== undefined) element.scrollTop = top;
      element.dispatchEvent(new Event("scroll", { bubbles: true }));
    });
  return { view, element, size, scroll };
}

describe("跟随底部", () => {
  it("内容一下子变高、位置没动时来了滚动事件：还在跟随，下一次内容变化滚到底", () => {
    const { view, element, size, scroll } = mount(1000);
    scroll(400);
    expect(stick).toBe(true);

    size.height = 1600;
    scroll();
    expect(stick).toBe(true);

    view.rerender(<Harness changed={1} />);
    expect(element.scrollTop).toBe(1600);
  });

  it("往上滚离开底部就不跟了，滚回底部又跟", () => {
    const { view, element, size, scroll } = mount(1000);
    scroll(400);
    scroll(100);
    expect(stick).toBe(false);

    size.height = 1600;
    view.rerender(<Harness changed={1} />);
    expect(element.scrollTop).toBe(100);

    scroll(1000);
    expect(stick).toBe(true);
  });

  it("没到底的往下滚不会重新开始跟随", () => {
    const { scroll } = mount(2000);
    scroll(1400);
    scroll(200);
    scroll(600);
    expect(stick).toBe(false);
  });
});
