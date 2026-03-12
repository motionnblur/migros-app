export type SupportRealtimeEventType = 'SUPPORT_UPDATED' | 'SUPPORT_MESSAGE_CREATED';

export interface ISupportRealtimeEvent {
  type: SupportRealtimeEventType;
  userMail: string;
  sender?: 'USER' | 'MANAGEMENT';
  messageId?: number;
}
