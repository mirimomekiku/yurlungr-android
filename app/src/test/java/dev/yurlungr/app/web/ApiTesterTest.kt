package dev.yurlungr.app.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiTesterTest {
    @Test fun buildsAJsonPost() {
        val built = ApiRequests.build(ApiRequest(method = "post", url = " http://127.0.0.1:8000/items ", headers = "Authorization: Bearer abc\n\n# note\nX-Trace:  1 ",
            bodyType = ApiBodyType.Json, body = "{\"a\":1}"))
        assertEquals("POST", built.method)
        assertEquals("http://127.0.0.1:8000/items", built.url)
        assertEquals(listOf("Authorization" to "Bearer abc", "X-Trace" to "1", "Content-Type" to "application/json"), built.headers)
        assertEquals("{\"a\":1}", String(built.body!!))
    }

    @Test fun keepsAContentTypeTheUserSet() {
        val built = ApiRequests.build(ApiRequest(method = "PUT", url = "https://example.com/x", headers = "content-type: application/vnd.api+json",
            bodyType = ApiBodyType.Json, body = "{}"))
        assertEquals(listOf("content-type" to "application/vnd.api+json"), built.headers)
    }

    @Test fun encodesForms() {
        assertEquals("name=Ada+Lovelace&note=a%26b%3Dc&empty=", ApiRequests.formEncode("name=Ada Lovelace\nnote=a&b=c\n\nempty"))
        val built = ApiRequests.build(ApiRequest(method = "POST", url = "http://localhost:5000/login", bodyType = ApiBodyType.Form, body = "user=me"))
        assertEquals("user=me", String(built.body!!))
        assertTrue("Content-Type" to "application/x-www-form-urlencoded" in built.headers)
    }

    @Test fun getAndHeadSendNoBody() {
        assertNull(ApiRequests.build(ApiRequest(method = "GET", url = "http://127.0.0.1:3000/", bodyType = ApiBodyType.Json, body = "{}")).body)
        assertNull(ApiRequests.build(ApiRequest(method = "HEAD", url = "http://127.0.0.1:3000/", bodyType = ApiBodyType.Text, body = "x")).body)
        assertNull(ApiRequests.build(ApiRequest(method = "POST", url = "http://127.0.0.1:3000/", bodyType = ApiBodyType.None, body = "x")).body)
    }

    @Test fun rejectsBadInput() {
        for (url in listOf("file:///etc/passwd", "javascript:alert(1)", "ftp://host/x", "127.0.0.1:3000", "http://", "")) {
            assertThrows(url, IllegalArgumentException::class.java) { ApiRequests.build(ApiRequest(url = url)) }
        }
        assertThrows(IllegalArgumentException::class.java) { ApiRequests.build(ApiRequest(method = "TRACE", url = "http://127.0.0.1/")) }
        assertThrows(IllegalArgumentException::class.java) { ApiRequests.build(ApiRequest(url = "http://127.0.0.1/", headers = "no colon here")) }
        assertThrows(IllegalArgumentException::class.java) { ApiRequests.build(ApiRequest(url = "http://127.0.0.1/", headers = "Bad Name: x")) }
    }

    @Test fun readsCharsetsAndSpotsJson() {
        assertEquals(Charsets.ISO_8859_1, ApiRequests.charsetOf("text/html; charset=ISO-8859-1"))
        assertEquals(Charsets.UTF_8, ApiRequests.charsetOf("application/json"))
        assertEquals(Charsets.UTF_8, ApiRequests.charsetOf("text/plain; charset=bogus-charset"))
        assertTrue(ApiRequests.looksLikeJson("application/problem+json", ""))
        assertTrue(ApiRequests.looksLikeJson("text/plain", "  [1,2]"))
    }

    @Test fun storageRoundTrips() {
        val requests = listOf(
            ApiRequest("List", "GET", "http://127.0.0.1:8000/items", "Accept: application/json", ApiBodyType.None, ""),
            ApiRequest("Create \"quoted\"", "POST", "http://127.0.0.1:8000/items", "", ApiBodyType.Json, "{\n  \"name\": \"ü\"\n}"),
        )
        val text = ApiRequestStore.encode("/data/projects/api", requests)
        assertEquals("/data/projects/api", Json.parseToJsonElement(text).jsonObject["project"]!!.jsonPrimitive.content)
        assertEquals(requests, ApiRequestStore.decode(text))
        assertEquals(emptyList<ApiRequest>(), ApiRequestStore.decode("not json"))
        assertEquals(listOf(ApiRequest(name = "x", method = "GET")), ApiRequestStore.decode("""{"requests":[{"name":"x","bodyType":"Nope"},3]}"""))
    }

    @Test fun storageKeysAreStableFileNames() {
        val key = ApiRequestStore.key("/data/data/com.termux/files/home/projects/api")
        assertEquals(64, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(key, ApiRequestStore.key("/data/data/com.termux/files/home/projects/api"))
        assertTrue(key != ApiRequestStore.key("/data/data/com.termux/files/home/projects/web"))
    }

    private val openApi3 = """
    {
      "openapi": "3.1.0",
      "servers": [{"url": "http://localhost:8000/api/v1"}],
      "paths": {
        "/items": {
          "get": {"summary": "List items"},
          "post": {
            "operationId": "create_item",
            "requestBody": {"content": {"application/json": {"schema": {"${'$'}ref": "#/components/schemas/Item"}}}}
          }
        },
        "/items/{id}": {
          "put": {"requestBody": {"content": {"application/json": {"example": {"name": "given"}}}}},
          "delete": {"description": "Removes it\nfor good"}
        },
        "/login": {
          "post": {"requestBody": {"content": {"application/x-www-form-urlencoded": {"schema": {"properties": {"username": {"type": "string"}, "password": {"type": "string", "example": "secret"}}}}}}}
        },
        "/loop": {"post": {"requestBody": {"content": {"application/json": {"schema": {"${'$'}ref": "#/components/schemas/Node"}}}}}}
      },
      "components": {"schemas": {
        "Item": {"type": "object", "properties": {
          "name": {"type": "string"}, "price": {"type": "number"}, "tags": {"type": "array", "items": {"type": "string"}},
          "kind": {"enum": ["a", "b"]}, "at": {"type": "string", "format": "date-time"}, "count": {"type": ["integer", "null"], "default": 3}
        }},
        "Node": {"type": "object", "properties": {"next": {"${'$'}ref": "#/components/schemas/Node"}}}
      }}
    }
    """.trimIndent()

    @Test fun parsesOpenApi3() {
        val routes = OpenApi.parse(openApi3)
        assertEquals(listOf("GET /items", "POST /items", "PUT /items/{id}", "DELETE /items/{id}", "POST /login", "POST /loop"), routes.map { "${it.method} ${it.path}" })
        assertEquals("List items", routes[0].summary)
        assertEquals(ApiBodyType.None, routes[0].bodyType)
        assertEquals("create_item", routes[1].summary)
        assertEquals("Removes it", routes[3].summary)
        val item = Json.parseToJsonElement(routes[1].body).jsonObject
        assertEquals(ApiBodyType.Json, routes[1].bodyType)
        assertEquals("string", item["name"]!!.jsonPrimitive.content)
        assertEquals("a", item["kind"]!!.jsonPrimitive.content)
        assertEquals("2024-01-01T00:00:00Z", item["at"]!!.jsonPrimitive.content)
        assertEquals(3, item["count"]!!.jsonPrimitive.int)
        assertEquals(1, item["tags"]!!.jsonArray.size)
        assertEquals(JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive("given"))), Json.parseToJsonElement(routes[2].body))
        assertEquals(ApiBodyType.Form, routes[4].bodyType)
        assertEquals("username=string\npassword=secret", routes[4].body)
        // A self-referencing schema ends instead of recursing forever.
        assertTrue(routes[5].body.startsWith("{"))
        assertEquals("/api/v1", OpenApi.basePath(openApi3))
    }

    @Test fun parsesSwagger2() {
        val swagger = """
        {"swagger": "2.0", "basePath": "/v2/",
         "paths": {"/pet": {"post": {"summary": "Add a pet", "parameters": [{"in": "body", "name": "body", "schema": {"${'$'}ref": "#/definitions/Pet"}}]}},
                   "/pet/{petId}": {"post": {"parameters": [{"in": "path", "name": "petId"}, {"in": "formData", "name": "name"}, {"in": "formData", "name": "status"}]}}},
         "definitions": {"Pet": {"properties": {"id": {"type": "integer", "format": "int64"}, "name": {"type": "string", "example": "doggie"}}}}}
        """.trimIndent()
        val routes = OpenApi.parse(swagger)
        assertEquals(2, routes.size)
        val pet = Json.parseToJsonElement(routes[0].body).jsonObject
        assertEquals(0, pet["id"]!!.jsonPrimitive.int)
        assertEquals("doggie", pet["name"]!!.jsonPrimitive.content)
        assertEquals(ApiBodyType.Form, routes[1].bodyType)
        assertEquals("name=\nstatus=", routes[1].body)
        assertEquals("/v2", OpenApi.basePath(swagger))
    }

    @Test fun toleratesJunk() {
        assertEquals(emptyList<ApiRoute>(), OpenApi.parse("[]"))
        assertEquals(emptyList<ApiRoute>(), OpenApi.parse("{\"paths\": 3}"))
        assertEquals(emptyList<ApiRoute>(), OpenApi.parse("not json"))
        assertEquals("", OpenApi.basePath("{\"servers\": [{\"url\": \"https://{host}/x/{v}\"}]}"))
        // External refs are never followed.
        assertEquals("{}", OpenApi.parse("""{"paths": {"/a": {"post": {"requestBody": {"content": {"application/json": {"schema": {"${'$'}ref": "https://evil.example/s.json"}}}}}}}}""").single().body)
    }
}
