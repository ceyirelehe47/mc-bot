import type { Context } from '@deepseek-ai/cordis'
import { defineTool } from '@deepseek-ai/dsh-tools'
import { createUserMessage } from '@deepseek-ai/dsh-llm'
import { install } from './plugin.mjs'

export const name = 'aibot-body'
export const inject = ['tools', 'sessionPersistence']

// Pinned to DSH 5dda764ed3aa172535a7967b06ff95d9cbfe536a.
// The native imports and this assembled entrypoint must be tested in that real checkout.
export function apply(ctx: Context) {
  install(ctx, { defineTool, createUserMessage })
}
