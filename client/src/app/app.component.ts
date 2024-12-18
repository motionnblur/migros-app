import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { DiscoverComponent } from '../views/discover-area/parent/discover-area.component';
import { RestService } from '../services/rest.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, DiscoverComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
  providers: [RestService],
})
export class AppComponent {
  constructor(public restService: RestService) {}
  title = 'migros-app';
}
