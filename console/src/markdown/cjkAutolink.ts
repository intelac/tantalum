// GFM 的自动链接只在空白处结束；中文里网址后面紧跟全角标点和文字时，整句都进了链接（N1，2026-11-14 实测 8091 看到）。
// remark-gfm 之后跑：自动识别出来的链接（文字就是网址本身）遇到第一个全角标点就截断，后面的变回普通文字。

interface Node {
  type: string;
  value?: string;
  url?: string;
  children?: Node[];
}

/** 中文、日文里常见的全角标点和括号。 */
const FULL_WIDTH = /[\uFF0C\u3002\uFF1B\uFF1A\uFF01\uFF1F\u3001\uFF09\u300D\u300F\u3011\u3009\u300B\uFF08\u300C\u300E\u3010\u3008\u300A\u2026\u2014\uFF5E\u201C\u201D\u2018\u2019]/;

function safeDecode(url: string): string {
  try {
    return decodeURI(url);
  } catch {
    return url;
  }
}

function split(parent: Node): void {
  const children = parent.children;
  if (children === undefined) return;
  for (let i = 0; i < children.length; i++) {
    const node = children[i] as Node;
    const only = node.children?.length === 1 ? node.children[0] : undefined;
    if (node.type === "link" && only?.type === "text" && only.value !== undefined && safeDecode(node.url ?? "") === only.value) {
      const cut = only.value.search(FULL_WIDTH);
      if (cut > 0) {
        const head = only.value.slice(0, cut);
        const rest = only.value.slice(cut);
        node.url = head;
        only.value = head;
        children.splice(i + 1, 0, { type: "text", value: rest });
        i++;
        continue;
      }
    }
    split(node);
  }
}

export function cjkAutolink() {
  return (tree: Node) => split(tree);
}
