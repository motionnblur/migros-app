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
import { categories } from '../memory/global-data';

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

  public categories = categories;

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
