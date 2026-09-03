// Generates sample "book page" PDFs and scanned-page PNGs used as demo attachments.
import { chromium } from 'playwright';
import fs from 'node:fs';
fs.mkdirSync('docs', { recursive: true });

const pages = {
  soil: {
    title: 'Properties of Clay Soils', sub: 'Field Survey Report · Chapter 4',
    paras: [
      'Clay soils are composed of very fine mineral particles, typically smaller than two micrometres in diameter. Because of this fine texture, clay retains water far longer than sandy soils and expands noticeably when wet.',
      'Engineers classify clay by its plasticity index. High plasticity clays shrink and swell with seasonal moisture, which can damage shallow foundations if the soil is not stabilised with lime or cement.',
      'Laboratory tests on the collected samples show moisture content rising steadily with depth, which matches the regional water-table survey carried out in the previous season.',
    ],
    table: [['Sample', 'Depth (m)', 'Moisture %'], ['A-01', '1.5', '24.3'], ['A-02', '3.0', '31.8'], ['A-03', '4.5', '36.1']],
  },
  solar: {
    title: 'Photovoltaic Systems Overview', sub: 'Renewable Energy Handbook · Section 2',
    paras: [
      'A photovoltaic system converts sunlight directly into electricity using semiconductor cells. Modern monocrystalline panels reach conversion efficiencies above twenty-two percent under standard test conditions.',
      'System output depends on irradiance, panel temperature and shading. A well-oriented rooftop array in North Africa can generate more than 1,800 kilowatt-hours per installed kilowatt each year.',
      'Inverters convert the direct current produced by the panels into alternating current for the grid. String inverters suit uniform roofs while micro-inverters handle partial shading better.',
    ],
    table: [['Panel type', 'Efficiency', 'Cost index'], ['Monocrystalline', '22.5%', '1.00'], ['Polycrystalline', '18.0%', '0.82'], ['Thin film', '13.5%', '0.65']],
  },
  water: {
    title: 'Water Desalination Methods', sub: 'Infrastructure Review · Part 3',
    paras: [
      'Reverse osmosis pushes seawater through semi-permeable membranes at high pressure, removing dissolved salts. It is the most energy-efficient large-scale desalination method in use today.',
      'Thermal methods such as multi-stage flash distillation remain common where waste heat from power plants is available, because the steam is effectively free.',
      'Brine disposal is the main environmental concern. Diffuser outfalls and dilution with cooling water reduce the local salinity impact on marine habitats.',
    ],
    table: [['Method', 'Energy (kWh/m³)', 'Share'], ['Reverse osmosis', '3.5', '69%'], ['Multi-stage flash', '12.0', '18%'], ['Multi-effect', '8.0', '9%']],
  },
  history: {
    title: 'Industrial Growth in the Delta', sub: 'Economic History · Volume 2',
    paras: [
      'Textile mills expanded rapidly along the river during the early twentieth century, drawing workers from surrounding villages and reshaping the regional economy.',
      'Rail links completed in the 1930s connected the mills to coastal ports, cutting transport time for finished cloth from weeks to days and opening export markets.',
      'By mid-century the sector employed more than a hundred thousand people, and its output financed the first generation of local engineering schools.',
    ],
    table: [['Decade', 'Mills', 'Workers'], ['1920s', '14', '9,500'], ['1930s', '31', '38,000'], ['1940s', '52', '104,000']],
  },
};

function html(p, scanned, pageNo) {
  const bg = scanned ? '#f6f1e6' : '#ffffff';
  const rot = scanned ? 'transform:rotate(-0.4deg);' : '';
  const rows = p.table.map((r, i) => `<tr>${r.map(c => `<${i ? 'td' : 'th'} style="border:1px solid #444;padding:8px 14px;text-align:left">${c}</${i ? 'td' : 'th'}>`).join('')}</tr>`).join('');
  return `<html><body style="margin:0;background:${bg}"><div style="padding:80px 90px;font-family:Georgia,'Times New Roman',serif;color:#1b1b1b;${rot}">
  <div style="font-size:12px;color:#777;letter-spacing:.2em;text-transform:uppercase">${p.sub}</div>
  <h1 style="font-size:36px;margin:8px 0 26px;font-weight:600">${p.title}</h1>
  ${p.paras.map(t => `<p style="font-size:19px;line-height:1.75;margin:0 0 18px;text-align:justify">${t}</p>`).join('')}
  <table style="border-collapse:collapse;font-size:17px;margin-top:14px">${rows}</table>
  <div style="position:absolute;bottom:40px;left:0;right:0;text-align:center;font-size:13px;color:#888">— ${pageNo} —</div>
  </div></body></html>`;
}

const browser = await chromium.launch({ channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1000, height: 1400 } });
let i = 0;
for (const [key, p] of Object.entries(pages)) {
  i++;
  // multi-page PDF: same chapter rendered on 3 pages with different page numbers
  const body = [1, 2, 3].map(n => `<div style="page-break-after:always;position:relative;height:1350px">${html(p, false, n).replace(/<\/?html>|<\/?body[^>]*>/g, '')}</div>`).join('');
  await page.setContent(`<html><body style="margin:0">${body}</body></html>`);
  await page.pdf({ path: `docs/${key}.pdf`, format: 'A4', printBackground: true });
  // scanned PNG of page 1
  await page.setContent(html(p, true, 1));
  await page.screenshot({ path: `docs/${key}_scan.png`, fullPage: true });
}
await browser.close();
console.log(fs.readdirSync('docs'));
