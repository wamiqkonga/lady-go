import { createContext, useContext } from 'react'

export type StepUpState = {
  /** Runs `action`; if the server demands a step-up, asks for a code and retries the action once. */
  runWithStepUp: <T>(action: () => Promise<T>) => Promise<T>
}

export const StepUpContext = createContext<StepUpState | null>(null)

export function useStepUp(): StepUpState {
  const ctx = useContext(StepUpContext)
  if (!ctx) throw new Error('useStepUp must be used within StepUpProvider')
  return ctx
}
