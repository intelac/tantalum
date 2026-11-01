import type { ReactNode } from "react";
import { Component, isValidElement } from "react";
import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import { zh } from "../text/zh";
import { cx } from "../ui/cx";
import { CodeBlock } from "./CodeBlock";
import "./markdown.css";

interface Props {
  text: string;
  /** 过程区里的中间文本用淡色。 */
  muted?: boolean;
  className?: string;
}

const SAFE_URL = /^(https?:|mailto:|#|\/|\.\/|\.\.\/)/i;

/** 只放行 http(s)、mailto 和相对地址；其余（如 javascript:）去掉。 */
export function safeUrl(url: string): string {
  return SAFE_URL.test(url.trim()) ? url : "";
}

interface CodeProps {
  className?: string;
  children?: ReactNode;
}

/** 围栏代码块从 pre 接管：取出 code 的语言和文本，交给 CodeBlock。 */
function Pre({ children }: { children?: ReactNode }) {
  if (isValidElement<CodeProps>(children)) {
    const match = /language-([\w+-]+)/.exec(children.props.className ?? "");
    const code = String(children.props.children ?? "");
    return <CodeBlock code={code} language={match?.[1]} />;
  }
  return <pre>{children}</pre>;
}

const COMPONENTS: Components = {
  pre: Pre,
  a: ({ href, children }) =>
    href === undefined || href === "" ? (
      <span>{children}</span>
    ) : (
      <a href={href} target="_blank" rel="noopener noreferrer">
        {children}
      </a>
    ),
  table: ({ children }) => (
    <div className="table-wrap">
      <table>{children}</table>
    </div>
  ),
};

interface BoundaryState {
  failed: boolean;
}

/** 渲染出错退回纯文本，不让一条消息拖垮整个列表。文本变化后再试一次：流式中间态失败不该让最终文本也停在回退。 */
class Boundary extends Component<{ text: string; children: ReactNode }, BoundaryState> {
  state: BoundaryState = { failed: false };

  static getDerivedStateFromError(): BoundaryState {
    return { failed: true };
  }

  componentDidUpdate(previous: { text: string }): void {
    if (this.state.failed && previous.text !== this.props.text) this.setState({ failed: false });
  }

  render() {
    if (this.state.failed) {
      return (
        <div>
          <p className="mb-2 text-xs text-fg-faint">{zh.error.markdown}</p>
          <pre className="whitespace-pre-wrap font-sans">{this.props.text}</pre>
        </div>
      );
    }
    return this.props.children;
  }
}

/** Markdown 正文。不渲染原始 HTML；链接只放行安全协议并在新窗口打开。 */
export function Markdown({ text, muted = false, className }: Props) {
  return (
    <div className={cx("md text-[15px]", muted && "md-muted", className)}>
      <Boundary text={text}>
        <ReactMarkdown remarkPlugins={[remarkGfm]} skipHtml urlTransform={safeUrl} components={COMPONENTS}>
          {text}
        </ReactMarkdown>
      </Boundary>
    </div>
  );
}
