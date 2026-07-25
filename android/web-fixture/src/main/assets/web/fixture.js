// 脚本用途：为离线语义页面提供可复位点击、输入、长按、动态 DOM 和稳定状态输出。
(() => {
  "use strict";

  const state = document.querySelector("#fixture-state");
  const setState = (value) => {
    if (state) {
      state.textContent = `STATE:${value}`;
      state.setAttribute("aria-label", `Fixture state ${value}`);
    }
    document.documentElement.dataset.fixtureState = value;
  };

  document.querySelector("#fixture-button")?.addEventListener("click", () => setState("clicked"));
  document.querySelector("#fixture-input")?.addEventListener("input", (event) => {
    setState(`input-${event.target.value}`);
  });

  const longPress = document.querySelector("#long-press-target");
  let longPressTimer;
  const cancelLongPress = () => {
    if (longPressTimer !== undefined) {
      clearTimeout(longPressTimer);
      longPressTimer = undefined;
    }
  };
  longPress?.addEventListener("pointerdown", () => {
    cancelLongPress();
    longPressTimer = setTimeout(() => setState("long-pressed"), 650);
  });
  longPress?.addEventListener("pointerup", cancelLongPress);
  longPress?.addEventListener("pointercancel", cancelLongPress);

  document.querySelector("#dynamic-trigger")?.addEventListener("click", () => {
    const container = document.querySelector("#dynamic-container");
    const button = document.createElement("button");
    button.id = "dynamic-button";
    button.type = "button";
    button.textContent = "Dynamic result";
    button.setAttribute("aria-label", "Dynamic result button");
    button.addEventListener("click", () => setState("dynamic-clicked"));
    container.replaceChildren(button);
    setState("dynamic-added");
  });

  document.querySelector("#scroll-target")?.addEventListener("click", () => setState("scrolled"));
  setState("ready");
})();
