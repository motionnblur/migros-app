export interface IChatMessage {
  id: number;
  sender: 'USER' | 'MANAGEMENT';
  message: string;
  createdAt: string;
}
