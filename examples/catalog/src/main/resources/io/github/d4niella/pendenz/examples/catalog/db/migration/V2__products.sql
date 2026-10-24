-- ${products} generated products across the eight categories, plus three that cause trouble on purpose.

WITH category (position, name, things, purpose) AS (
    VALUES
        (0, 'Kitchen', ARRAY['frying pan', 'chef''s knife', 'kettle', 'cutting board', 'spice rack'], 'for cooking at home'),
        (1, 'Garden', ARRAY['garden hose', 'pruning shears', 'watering can', 'compost bin', 'rake'], 'for beds and lawns'),
        (2, 'Electronics', ARRAY['headphones', 'power bank', 'webcam', 'bluetooth speaker', 'e-reader'], 'with a rechargeable battery'),
        (3, 'Toys', ARRAY['building blocks', 'plush bear', 'puzzle', 'toy train', 'kite'], 'for children from three years'),
        (4, 'Books', ARRAY['cookbook', 'crime novel', 'travel guide', 'poetry collection', 'atlas'], 'in hardcover, 320 pages'),
        (5, 'Sports', ARRAY['yoga mat', 'tennis racket', 'running belt', 'dumbbell set', 'football'], 'for training twice a week'),
        (6, 'Clothing', ARRAY['rain jacket', 'wool sweater', 'linen shirt', 'winter hat', 'hiking socks'], 'in sizes S to XXL'),
        (7, 'Office', ARRAY['stapler', 'desk organiser', 'notebook', 'fountain pen', 'filing cabinet'], 'for a tidy desk')
),
adjective (words) AS (
    VALUES (ARRAY['sturdy', 'compact', 'classic', 'lightweight', 'premium', 'colourful', 'practical'])
)
INSERT INTO product (name, description, expected_category)
SELECT initcap(adj) || ' ' || thing,
       'A ' || adj || ' ' || thing || ' ' || category.purpose || '.',
       category.name
FROM generate_series(1, ${products}) AS n
JOIN category ON category.position = n % 8
CROSS JOIN adjective
CROSS JOIN LATERAL (
    SELECT category.things[1 + (n / 8) % array_length(category.things, 1)] AS thing,
           adjective.words[1 + (n * 3) % array_length(adjective.words, 1)] AS adj
) AS pick
ORDER BY n;

-- No description: the handler cannot classify them and fails every attempt, until an operator adds one.
INSERT INTO product (name, description, expected_category) VALUES ('Mystery box', NULL, 'Toys');
INSERT INTO product (name, description, expected_category) VALUES ('Desk lamp', NULL, 'Office');
-- Recalled: the handler refuses it every time; an operator takes it out of the run.
INSERT INTO product (name, description, recalled, expected_category)
VALUES ('Pressure cooker XL', 'Recalled after a safety warning.', true, 'Kitchen');
