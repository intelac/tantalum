// 每天的笔记和“自动记下”开关的数据（memory-capture）：写入后让列表和那一篇失效。

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { deleteNote, getCapture, getNote, listNotes, saveNote, setCapture } from "../api/client";

export const NOTES_KEY = ["memory-notes"] as const;
export const CAPTURE_KEY = ["memory-capture"] as const;

export function useNotes() {
  return useQuery({ queryKey: NOTES_KEY, queryFn: listNotes, select: (data) => data.days });
}

export function useNote(day: string, session: string) {
  return useQuery({ queryKey: [...NOTES_KEY, day, session], queryFn: () => getNote(day, session) });
}

export function useSaveNote(day: string, session: string) {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: (text: string) => saveNote(day, session, text),
    onSuccess: () => queries.invalidateQueries({ queryKey: NOTES_KEY }),
  });
}

export function useDeleteNote(day: string, session: string) {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: () => deleteNote(day, session),
    onSuccess: () => queries.invalidateQueries({ queryKey: NOTES_KEY }),
  });
}

export function useCapture() {
  return useQuery({ queryKey: CAPTURE_KEY, queryFn: getCapture });
}

export function useSetCapture() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: (enabled: boolean) => setCapture(enabled),
    onSuccess: (state) => queries.setQueryData(CAPTURE_KEY, state),
  });
}
