import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { AppFrame } from "./AppFrame";

beforeEach(() => {
  localStorage.clear();
  Object.defineProperty(window, "innerWidth", { configurable: true, value: 1400 });
  // jsdom 没有指针捕获：给一个空实现
  HTMLElement.prototype.setPointerCapture = () => undefined;
  HTMLElement.prototype.releasePointerCapture = () => undefined;
});
afterEach(cleanup);

function frame() {
  render(
    <AppFrame header={<div />} sidebar={() => <div>侧栏</div>}>
      <div>主区</div>
    </AppFrame>,
  );
  return { separator: screen.getByRole("separator"), aside: screen.getByRole("complementary") };
}

describe("AppFrame divider", () => {
  it("drags with the left button and stops when it is released", () => {
    const { separator, aside } = frame();
    fireEvent.pointerDown(separator, { pointerId: 1, button: 0, buttons: 1, clientX: 280 });
    fireEvent.pointerMove(separator, { pointerId: 1, buttons: 1, clientX: 340 });
    expect(aside.style.width).toBe("340px");
    fireEvent.pointerUp(separator, { pointerId: 1, button: 0, buttons: 0, clientX: 340 });
    fireEvent.pointerMove(separator, { pointerId: 1, buttons: 0, clientX: 420 });
    expect(aside.style.width).toBe("340px");
  });

  /** 右键按下以后系统菜单吞掉了松开：以前之后只要鼠标移过分割线，侧栏就跟着动。 */
  it("a right click does not start a drag", () => {
    const { separator, aside } = frame();
    const before = aside.style.width;
    fireEvent.pointerDown(separator, { pointerId: 1, button: 2, buttons: 2, clientX: 280 });
    fireEvent.pointerMove(separator, { pointerId: 1, buttons: 0, clientX: 400 });
    expect(aside.style.width).toBe(before);
  });

  it("a lost release does not leave the divider following the mouse", () => {
    const { separator, aside } = frame();
    fireEvent.pointerDown(separator, { pointerId: 1, button: 0, buttons: 1, clientX: 280 });
    fireEvent.pointerMove(separator, { pointerId: 1, buttons: 1, clientX: 320 });
    // 松开的事件没来，之后鼠标没按键移过
    fireEvent.pointerMove(separator, { pointerId: 1, buttons: 0, clientX: 420 });
    expect(aside.style.width).toBe("320px");
    fireEvent.pointerMove(separator, { pointerId: 1, buttons: 0, clientX: 450 });
    expect(aside.style.width).toBe("320px");
  });
});
