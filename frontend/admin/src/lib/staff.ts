import type { components } from './api-types'

export type StaffUser = components['schemas']['AdminUserResponse']
export const STAFF_ROLES = ['SUPPORT', 'ADMIN', 'SUPER_ADMIN'] as const
export type StaffRole = (typeof STAFF_ROLES)[number]

export function statusLabel(user: StaffUser): string {
  if (user.pendingInvite) return 'Invited'
  return user.status === 'ACTIVE' ? 'Active' : 'Deactivated'
}
