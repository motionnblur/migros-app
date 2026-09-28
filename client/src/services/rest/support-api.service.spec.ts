import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SupportApiService } from './support-api.service';

describe('SupportApiService API contracts', () => {
  let service: SupportApiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(SupportApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('gets user support messages and posts a message body', () => {
    service.getSupportMessages().subscribe();
    service.sendSupportMessage('Need help').subscribe();
    const messages = httpMock.expectOne('/user/support/messages');
    const send = httpMock.expectOne('/user/support/send');
    expect(messages.request.method).toBe('GET');
    expect(send.request.method).toBe('POST');
    expect(send.request.body).toEqual({ message: 'Need help' });
    messages.flush([]);
    send.flush('', { status: 200, statusText: 'OK' });
  });

  it('preserves admin search query parameters and default limit', () => {
    service.searchSupportCustomersForAdmin('alex').subscribe();
    const request = httpMock.expectOne('/admin/panel/support/customers?query=alex&limit=20');
    expect(request.request.method).toBe('GET');
    request.flush([]);
  });

  it('preserves admin reply, edit, delete, and ban request methods, URLs, parameters, and bodies', () => {
    service.sendSupportReplyFromAdmin('user@example.com', 'Reply').subscribe();
    service.editSupportMessageForAdmin('user@example.com', 7, 'Edited').subscribe();
    service.deleteSupportMessageForAdmin('user@example.com', 7).subscribe();
    service.banSupportUserFromAdmin('user@example.com').subscribe();

    const reply = httpMock.expectOne('/admin/panel/support/reply');
    const edit = httpMock.expectOne('/admin/panel/support/messages/7');
    const remove = httpMock.expectOne('/admin/panel/support/messages/7?userMail=user@example.com');
    const ban = httpMock.expectOne('/admin/panel/support/ban?userMail=user@example.com');
    expect(reply.request.method).toBe('POST');
    expect(reply.request.body).toEqual({ userMail: 'user@example.com', message: 'Reply' });
    expect(edit.request.method).toBe('PATCH');
    expect(edit.request.body).toEqual({ userMail: 'user@example.com', message: 'Edited' });
    expect(remove.request.method).toBe('DELETE');
    expect(remove.request.params.get('userMail')).toBe('user@example.com');
    expect(ban.request.method).toBe('POST');
    expect(ban.request.body).toBeNull();
    expect(ban.request.params.get('userMail')).toBe('user@example.com');
    [reply, edit, remove, ban].forEach((request) => request.flush('', { status: 200, statusText: 'OK' }));
  });
});
