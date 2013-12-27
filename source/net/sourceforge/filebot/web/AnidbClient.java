package net.sourceforge.filebot.web;

import static net.sourceforge.filebot.web.EpisodeUtilities.*;
import static net.sourceforge.filebot.web.WebRequest.*;
import static net.sourceforge.tuned.XPathUtilities.*;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.swing.Icon;

import net.sourceforge.filebot.Cache;
import net.sourceforge.filebot.ResourceManager;

import org.w3c.dom.Document;
import org.w3c.dom.Node;

public class AnidbClient extends AbstractEpisodeListProvider {

	private static final FloodLimit REQUEST_LIMIT = new FloodLimit(5, 12, TimeUnit.SECONDS); // no more than 5 requests within a 10 second window (+2 seconds for good measure)

	private final String host = "anidb.net";

	private final String client;
	private final int clientver;

	public AnidbClient(String client, int clientver) {
		this.client = client;
		this.clientver = clientver;
	}

	@Override
	public String getName() {
		return "AniDB";
	}

	@Override
	public Icon getIcon() {
		return ResourceManager.getIcon("search.anidb");
	}

	@Override
	public boolean hasSingleSeasonSupport() {
		return false;
	}

	@Override
	public boolean hasLocaleSupport() {
		return true;
	}

	@Override
	public ResultCache getCache() {
		return new ResultCache(host, Cache.getCache("web-datasource-lv2"));
	}

	@Override
	public List<SearchResult> search(String query, final Locale locale) throws Exception {
		// bypass automatic caching since search is based on locally cached data anyway
		return fetchSearchResult(query, locale);
	}

	@Override
	public List<SearchResult> fetchSearchResult(String query, final Locale locale) throws Exception {
		LocalSearch<SearchResult> index = new LocalSearch<SearchResult>(getAnimeTitles()) {

			@Override
			protected Set<String> getFields(SearchResult it) {
				return set(it.getEffectiveNames());
			}
		};

		return new ArrayList<SearchResult>(index.search(query));
	}

	@Override
	public List<Episode> fetchEpisodeList(SearchResult searchResult, SortOrder sortOrder, Locale language) throws Exception {
		AnidbSearchResult anime = (AnidbSearchResult) searchResult;

		// e.g. http://api.anidb.net:9001/httpapi?request=anime&client=filebot&clientver=1&protover=1&aid=4521
		URL url = new URL("http", "api." + host, 9001, "/httpapi?request=anime&client=" + client + "&clientver=" + clientver + "&protover=1&aid=" + anime.getAnimeId());

		// respect flood protection limits
		REQUEST_LIMIT.acquirePermit();

		// get anime page as xml
		Document dom = getDocument(url);

		// select main title and anime start date
		Date seriesStartDate = Date.parse(selectString("//startdate", dom), "yyyy-MM-dd");
		String animeTitle = selectString("//titles/title[@type='official' and @lang='" + language.getLanguage() + "']", dom);
		if (animeTitle.isEmpty()) {
			animeTitle = selectString("//titles/title[@type='main']", dom);
		}

		List<Episode> episodes = new ArrayList<Episode>(25);

		for (Node node : selectNodes("//episode", dom)) {
			Node epno = getChild("epno", node);
			int number = Integer.parseInt(getTextContent(epno).replaceAll("\\D", ""));
			int type = Integer.parseInt(getAttribute("type", epno));

			if (type == 1 || type == 2) {
				Date airdate = Date.parse(getTextContent("airdate", node), "yyyy-MM-dd");
				String title = selectString(".//title[@lang='" + language.getLanguage() + "']", node);
				if (title.isEmpty()) { // English language fall-back
					title = selectString(".//title[@lang='en']", node);
				}

				if (type == 1) {
					episodes.add(new Episode(animeTitle, seriesStartDate, null, number, title, number, null, airdate, searchResult)); // normal episode, no seasons for anime
				} else {
					episodes.add(new Episode(animeTitle, seriesStartDate, null, null, title, null, number, airdate, searchResult)); // special episode
				}
			}
		}

		// make sure episodes are in ordered correctly
		sortEpisodes(episodes);

		// sanity check
		if (episodes.isEmpty()) {
			// anime page xml doesn't work sometimes
			Logger.getLogger(AnidbClient.class.getName()).log(Level.WARNING, String.format("Unable to parse any episode data from xml: %s (%d)", anime, anime.getAnimeId()));
		}

		return episodes;
	}

	@Override
	public URI getEpisodeListLink(SearchResult searchResult) {
		try {
			return new URI("http", host, "/a" + ((AnidbSearchResult) searchResult).getAnimeId(), null);
		} catch (URISyntaxException e) {
			throw new RuntimeException(e);
		}
	}

	public synchronized List<AnidbSearchResult> getAnimeTitles() throws Exception {
		URL url = new URL("http", host, "/api/anime-titles.dat.gz");
		ResultCache cache = getCache();

		@SuppressWarnings("unchecked")
		List<AnidbSearchResult> anime = (List) cache.getSearchResult(null, Locale.ROOT);
		if (anime != null) {
			return anime;
		}

		// <aid>|<type>|<language>|<title>
		// type: 1=primary title (one per anime), 2=synonyms (multiple per anime), 3=shorttitles (multiple per anime), 4=official title (one per language)
		Pattern pattern = Pattern.compile("^(?!#)(\\d+)[|](\\d)[|]([\\w-]+)[|](.+)$");

		Map<Integer, String> primaryTitleMap = new HashMap<Integer, String>();
		Map<Integer, Map<String, String>> officialTitleMap = new HashMap<Integer, Map<String, String>>();
		Map<Integer, Map<String, String>> synonymsTitleMap = new HashMap<Integer, Map<String, String>>();

		// fetch data
		Scanner scanner = new Scanner(new GZIPInputStream(url.openStream()), "UTF-8");

		try {
			while (scanner.hasNextLine()) {
				Matcher matcher = pattern.matcher(scanner.nextLine());

				if (matcher.matches()) {
					int aid = Integer.parseInt(matcher.group(1));
					String type = matcher.group(2);
					String language = matcher.group(3);
					String title = matcher.group(4);

					if (type.equals("1")) {
						primaryTitleMap.put(aid, title);
					} else if (type.equals("2") || type.equals("4")) {
						Map<Integer, Map<String, String>> titleMap = (type.equals("4") ? officialTitleMap : synonymsTitleMap);
						Map<String, String> languageTitleMap = titleMap.get(aid);
						if (languageTitleMap == null) {
							languageTitleMap = new HashMap<String, String>();
							titleMap.put(aid, languageTitleMap);
						}

						languageTitleMap.put(language, title);
					}
				}
			}
		} finally {
			scanner.close();
		}

		// build up a list of all possible AniDB search results
		anime = new ArrayList<AnidbSearchResult>(primaryTitleMap.size());

		for (Entry<Integer, String> entry : primaryTitleMap.entrySet()) {
			Map<String, String> localizedTitles = new HashMap<String, String>();
			if (synonymsTitleMap.containsKey(entry.getKey())) {
				localizedTitles.putAll(synonymsTitleMap.get(entry.getKey())); // use synonym as fallback
			}
			if (officialTitleMap.containsKey(entry.getKey())) {
				localizedTitles.putAll(officialTitleMap.get(entry.getKey())); // primarily use official title if available
			}

			String englishTitle = localizedTitles.get("en"); // ONLY SUPPORT ENGLISH LOCALIZATION
			anime.add(new AnidbSearchResult(entry.getKey(), entry.getValue(), englishTitle == null || englishTitle.isEmpty() ? new String[] {} : new String[] { englishTitle }));
		}

		// populate cache
		return cache.putSearchResult(null, Locale.ROOT, anime);
	}

}
