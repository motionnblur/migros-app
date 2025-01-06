import {
  Directive,
  ElementRef,
  EventEmitter,
  Input,
  Output,
  SimpleChanges,
  ViewChild,
} from '@angular/core';
import { FormControl } from '@angular/forms';
import { RestService } from '../services/rest/rest.service';
import { EventService } from '../services/event/event.service';

@Directive()
export abstract class ProductAdderBase {
  @Input() productName!: string;
  @Input() price!: number;
  @Input() count!: number;
  @Input() discount!: number;
  @Input() description!: string;
  @Input() selectedImage: File | null = null;

  @Output() hasEscapePressed = new EventEmitter<boolean>();

  @ViewChild('imageUploaderRef')
  imageUploaderRef!: ElementRef<HTMLInputElement>;
  @ViewChild('imageRef') imageRef!: ElementRef<HTMLImageElement>;
  @ViewChild('addImageRef') addImageRef!: ElementRef<HTMLDivElement>;

  @Output() hasProductAdded = new EventEmitter<boolean>();

  selectedFormValue: any = null;
  protected imageUrl: string | null = null;
  protected categoryControl = new FormControl('');

  protected boundKeyDownEvent!: (event: KeyboardEvent) => void;

  public isUpdateMode: boolean = false;

  public categories = [
    { value: 1, name: 'Yılbaşı' },
    { value: 2, name: 'Meyve, Sebze' },
    { value: 3, name: 'Süt, Kahvaltılık' },
    { value: 4, name: 'Temel Gıda' },
    { value: 5, name: 'Meze, Hazır yemek, Donut' },
    { value: 6, name: 'İçecek' },
    { value: 7, name: 'Dondurma' },
    { value: 8, name: 'Atistirmalik' },
    { value: 9, name: 'Fırın, Pastane' },
    { value: 10, name: 'Deterjan, Temizlik' },
    { value: 11, name: 'Kağıt, Islak mendil' },
    { value: 12, name: 'Kişisel Bakım,Kozmetik, Sağlık' },
    { value: 13, name: 'Bebek' },
    { value: 14, name: 'Ev, Yaşam' },
    { value: 15, name: 'Kitap, Kırtasiye, Oyuncak' },
    { value: 16, name: 'Çiçek' },
    { value: 17, name: 'Pet Shop' },
    { value: 18, name: 'Elektronik' },
  ];

  constructor(
    protected restService: RestService,
    protected eventManager: EventService
  ) {
    this.boundKeyDownEvent = this.keyDownEvent.bind(this);
  }

  ngOnChanges(changes: SimpleChanges) {
    if (changes['selectedImage']) {
      this.updateView(changes['selectedImage'].currentValue);
    }
  }
  ngOnInit() {
    document.addEventListener('keydown', this.boundKeyDownEvent);
    this.categoryControl.valueChanges.subscribe((value) => {
      //console.log('Selected value:', value);
      if (value !== null && value !== undefined) {
        this.selectedFormValue = value;
      }
    });
  }

  ngOnDestroy() {
    document.removeEventListener('keydown', this.boundKeyDownEvent);
  }

  public outProductAdded() {
    this.hasProductAdded.emit(true);
  }

  public openImageUploader() {
    if (this.imageUploaderRef) {
      this.imageUploaderRef.nativeElement.click();
    }
  }
  public onImageSelected(event: any) {
    if (event.target.files && event.target.files.length > 0) {
      this.selectedImage = event.target.files[0];
      this.updateView(this.selectedImage);
    }
  }
  private updateView(image: File | null) {
    if (image) {
      this.imageUrl = URL.createObjectURL(image);
    } else {
      this.imageUrl = null;
    }
  }

  protected keyDownEvent(event: KeyboardEvent) {
    if (event.key === 'Escape') {
      this.hasEscapePressed.emit(true);
    }
  }

  openEditor?(): void {}
}
