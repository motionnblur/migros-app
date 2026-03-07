import { IUserOrderDetail } from './IUserOrderDetail';

export interface IUserOrderGroup {
  orderGroupId: number;
  createdAt?: string | null;
  items: IUserOrderDetail[];
}
