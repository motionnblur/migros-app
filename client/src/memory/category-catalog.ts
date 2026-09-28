export interface CategoryDefinition {
  readonly value: number;
  readonly name: string;
  readonly image: string;
  readonly keywords: readonly string[];
}

export const CATEGORY_CATALOG: readonly CategoryDefinition[] = [
  {
    value: 1,
    name: 'Yılbaşı',
    image: '/discover-items/yilbasi.png',
    keywords: ['hediye', 'yılbaşı'],
  },
  {
    value: 2,
    name: 'Meyve, Sebze',
    image: '/discover-items/meyve.png',
    keywords: ['manav', 'meyve', 'sebze'],
  },
  {
    value: 3,
    name: 'Süt, Kahvaltılık',
    image: '/discover-items/stkvlt.png',
    keywords: ['süt', 'peynir', 'yumurta', 'kahvaltı'],
  },
  {
    value: 4,
    name: 'Temel Gıda',
    image: '/discover-items/temelgida.png',
    keywords: ['makarna', 'pirinç', 'bakliyat', 'yağ'],
  },
  {
    value: 5,
    name: 'Meze, Hazır Yemek, Donut',
    image: '/discover-items/haziryemek.png',
    keywords: ['meze', 'hazır yemek', 'donut'],
  },
  {
    value: 6,
    name: 'İçecek',
    image: '/discover-items/icecek.png',
    keywords: ['su', 'çay', 'kahve', 'içecek'],
  },
  {
    value: 7,
    name: 'Dondurma',
    image: '/discover-items/dondurma.png',
    keywords: ['dondurma'],
  },
  {
    value: 8,
    name: 'Atıştırmalık',
    image: '/discover-items/atistirma.png',
    keywords: ['çikolata', 'cips', 'atıştırmalık'],
  },
  {
    value: 9,
    name: 'Fırın, Pastane',
    image: '/discover-items/firin.png',
    keywords: ['ekmek', 'pasta', 'poğaça', 'fırın'],
  },
  {
    value: 10,
    name: 'Deterjan, Temizlik',
    image: '/discover-items/temizlik.png',
    keywords: ['deterjan', 'temizlik'],
  },
  {
    value: 11,
    name: 'Kağıt, Islak Mendil',
    image: '/discover-items/islakmendil.png',
    keywords: ['tuvalet kağıdı', 'kağıt havlu', 'ıslak mendil'],
  },
  {
    value: 12,
    name: 'Kişisel Bakım, Kozmetik, Sağlık',
    image: '/discover-items/Kozmetik.png',
    keywords: ['şampuan', 'kozmetik', 'kişisel bakım', 'sağlık'],
  },
  {
    value: 13,
    name: 'Bebek',
    image: '/discover-items/bebek.png',
    keywords: ['bebek bezi', 'bebek'],
  },
  {
    value: 14,
    name: 'Ev, Yaşam',
    image: '/discover-items/evyasam.png',
    keywords: ['ev', 'mutfak', 'yaşam'],
  },
  {
    value: 15,
    name: 'Kitap, Kırtasiye, Oyuncak',
    image: '/discover-items/kirtasiye.png',
    keywords: ['kitap', 'kalem', 'kırtasiye', 'oyuncak'],
  },
  {
    value: 16,
    name: 'Çiçek',
    image: '/discover-items/cicek.png',
    keywords: ['çiçek'],
  },
  {
    value: 17,
    name: 'Pet Shop',
    image: '/discover-items/petshop.png',
    keywords: ['kedi', 'köpek', 'mama', 'pet'],
  },
  {
    value: 18,
    name: 'Elektronik',
    image: '/discover-items/elektronik.png',
    keywords: ['telefon', 'elektronik'],
  },
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
    normalizeCategorySearch([category.name, ...category.keywords].join(' ')).includes(
      normalizedQuery
    )
  );
}
