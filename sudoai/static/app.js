const token = document.querySelector('meta[name="sudoai-csrf"]').content;
const notice = document.querySelector('#notice');
const dialog = document.querySelector('#import-dialog');

async function api(path, method, body) {
  const headers = {'X-SudoAI-CSRF': token};
  const options = {method, headers};
  if (body instanceof FormData) options.body = body;
  else if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(body);
  }
  const response = await fetch(path, options);
  const result = await response.json();
  if (!response.ok) throw new Error(typeof result.detail === 'string' ? result.detail : 'The request could not be completed.');
  return result;
}

function bind(selector, event, handler) {
  for (const element of document.querySelectorAll(selector)) {
    element.addEventListener(event, async e => {
      e.preventDefault();
      try { await handler(element); }
      catch (err) { notice.textContent = err.message; }
    });
  }
}

bind('#open-import', 'click', () => dialog.showModal());
bind('.cancel', 'click', () => dialog.close());
bind('.import-form', 'submit', async form => {
  const status = form.querySelector('.form-status');
  const data = new FormData(form);
  if (form.dataset.session) data.set('session_id', form.dataset.session);
  const button = form.querySelector('[type=submit]');
  button.disabled = true;
  status.textContent = 'Importing…';
  try {
    const result = await api('/api/v1/import', 'POST', data);
    location.assign('/sessions/' + result.id);
  } catch (error) {
    status.textContent = error.message;
  } finally { button.disabled = false; }
});

bind('.ask-form', 'submit', async form => {
  const box = form.parentElement.querySelector('.answer');
  const button = form.querySelector('button');
  button.disabled = true;
  box.textContent = 'Searching meeting evidence…';
  try {
    const result = await api('/api/v1/ask','POST',{question:form.elements.question.value,session_id:form.dataset.session || null});
    const p = document.createElement('p');
    p.textContent = result.answer;
    box.replaceChildren(p);
    for (const ref of result.references) {
      const link = document.createElement('a');
      const sec = Math.floor(ref.at_ms / 1000);
      link.href = '/sessions/' + ref.session_id + '#e-' + ref.id;
      link.textContent = Math.floor(sec / 60) + ':' + String(sec % 60).padStart(2,'0') + ' · ' + ref.quote;
      box.append(link);
    }
  } catch (err) { box.textContent = err.message; }
  finally { button.disabled = false; }
});

bind('.check-task','change',async input => {
  try { await api('/api/v1/sessions/' + input.dataset.session + '/tasks/' + input.dataset.task,'PUT',{completed:input.checked}); }
  catch (err) { input.checked = !input.checked; throw err; }
});
bind('#signout','click',async () => { await api('/api/v1/auth/revoke','POST'); location.assign('/'); });
bind('#retry','click',async button => { await api('/api/v1/sessions/' + button.dataset.session + '/retry','POST'); location.reload(); });
bind('#delete','click',async button => {
  if (confirm('Delete this meeting, its media, and its searchable evidence?')) {
    await api('/api/v1/sessions/' + button.dataset.session,'DELETE');
    location.assign('/');
  }
});
