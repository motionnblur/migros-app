import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { DiscoverComponent } from '../../components/discover-area/parent/discover-area.component';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, DiscoverComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
})
export class AppComponent {
  title = 'migros-app';
}
