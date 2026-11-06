import { ArrowDown } from "lucide-react";
import { useText } from "../text";
import { Button } from "../ui/Button";

interface Props {
  onClick: () => void;
}

/** 用户向上翻看时出现的圆形按钮。 */
export function JumpToBottom({ onClick }: Props) {
  const t = useText();
  return (
    <Button
      variant="secondary"
      size="sm"
      aria-label={t.conversation.jumpToBottom}
      title={t.conversation.jumpToBottom}
      onClick={onClick}
      className="absolute bottom-3 left-1/2 h-8 w-8 -translate-x-1/2 rounded-full px-0 shadow-md"
    >
      <ArrowDown size={16} />
    </Button>
  );
}
