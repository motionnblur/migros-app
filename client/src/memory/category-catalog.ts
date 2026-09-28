export interface CategoryDefinition {
  readonly value: number;
  readonly name: string;
  readonly image: string;
}

export const CATEGORY_CATALOG: readonly CategoryDefinition[] = [
  { value: 1, name: 'Yılbaşı', image: '/discover-items/yilbasi.png' },
  { value: 2, name: 'Meyve, Sebze', image: '/discover-items/meyve.png' },
  { value: 3, name: 'Süt, Kahvaltılık', image: '/discover-items/stkvlt.png' },
  { value: 4, name: 'Temel Gıda', image: '/discover-items/temelgida.png' },
  { value: 5, name: 'Meze, Hazır Yemek, Donut', image: '/discover-items/haziryemek.png' },
  { value: 6, name: 'İçecek', image: '/discover-items/icecek.png' },
  { value: 7, name: 'Dondurma', image: '/discover-items/dondurma.png' },
  { value: 8, name: 'Atıştırmalık', image: '/discover-items/atistirma.png' },
  { value: 9, name: 'Fırın, Pastane', image: '/discover-items/firin.png' },
  { value: 10, name: 'Deterjan, Temizlik', image: '/discover-items/temizlik.png' },
  { value: 11, name: 'Kağıt, Islak Mendil', image: '/discover-items/islakmendil.png' },
  {
    value: 12,
    name: 'Kişisel Bakım, Kozmetik, Sağlık',
    image: '/discover-items/Kozmetik.png',
  },
  { value: 13, name: 'Bebek', image: '/discover-items/bebek.png' },
  { value: 14, name: 'Ev, Yaşam', image: '/discover-items/evyasam.png' },
  { value: 15, name: 'Kitap, Kırtasiye, Oyuncak', image: '/discover-items/kirtasiye.png' },
  { value: 16, name: 'Çiçek', image: '/discover-items/cicek.png' },
  { value: 17, name: 'Pet Shop', image: '/discover-items/petshop.png' },
  { value: 18, name: 'Elektronik', image: '/discover-items/elektronik.png' },
];

export function normalizeCategorySearch(value: string): string {
  return (value || '')
    .trim()
    .toLocaleLowerCase('tr-TR')
    .replace(/ı/g, 'i')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
}

export function filterCategoriesByQuery(
  catalog: readonly CategoryDefinition[],
  query: string
): CategoryDefinition[] {
  const normalizedQuery = normalizeCategorySearch(query);
  if (!normalizedQuery) {
    return [...catalog];
  }

  return catalog.filter((category) =>
    normalizeCategorySearch(category.name).includes(normalizedQuery)
  );
}
