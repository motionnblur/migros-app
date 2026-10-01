import { ProductUnitPriceBasis } from './IProductPackageMetadata';

export interface IProductPreview {
  productId: number;
  productName: string;
  productPrice: number;
  productCount: number;
  /**
   * How much is in one package, or `null` when the product has no package size.
   *
   * Optional because the overwhelming majority of products have none: the column
   * was added nullable and deliberately not backfilled, since a size inferred
   * from a product name is a unit price that looks authoritative and is wrong.
   * `undefined` covers a response from a server that predates the field; both
   * mean "show no package line" rather than "show a placeholder".
   */
  packageAmount?: number | null;
  /** The unit `packageAmount` is counted in: `G`, `KG`, `ML`, `L` or `ADET`. */
  packageUnit?: string | null;
  /**
   * The package price per kilogram, litre or item, already rounded by the server.
   *
   * Never derived here. The backend computes it from the same effective price
   * `productPrice` carries, so a unit price rendered beside the package price
   * cannot describe a different price than the one the customer pays.
   */
  unitPrice?: number | null;
  /** What `unitPrice` is per. Named rather than inferred from `packageUnit`. */
  unitPriceBasis?: ProductUnitPriceBasis | string | null;
}
